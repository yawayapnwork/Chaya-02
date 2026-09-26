// chaya-navmesh: bakes a Detour navmesh from triangle geometry with the real Recast build pipeline, and answers path
// queries on it with the real Detour query. Everything geometric is done by recastnavigation (Recast + Detour); this
// file reads an OBJ, turns metre-denominated settings into Recast's voxel config, calls the library in the order its
// own RecastDemo solo-mesh sample does, and serialises the result.
//
// Coordinates: this tool only ever sees Recast's frame (metres, +Y up). The conversion from and to the Chaya canonical
// frame (metres, +Z up) happens in exactly one place, services/reconstruction/chaya_worker/recast_boundary.py.
//
//   chaya-navmesh --version
//   chaya-navmesh bake --input geometry.obj --navmesh out.navmesh --report out.json <config flags, all required>
//   chaya-navmesh path --navmesh in.navmesh --start X Y Z --end X Y Z --half-extents X Y Z --output path.json
//
// Input OBJ: `v x y z` and `f a b c ...` lines (polygons are fan-triangulated; `a/b/c` and negative indices are
// accepted). Faces under a group or object whose name starts with "obstacle" are blocked geometry: they are rasterised
// as solid, never walkable, whatever their slope. All other faces are walkable only if Recast's own slope test passes.
//
// Exit status (also written to the report as "status"):
//   0 OK   2 INVALID_ARGUMENTS   3 INVALID_GEOMETRY   4 NO_WALKABLE_SURFACE   5 NAVMESH_BUILD_FAILED
//   6 PATH_NOT_FOUND   7 IO_ERROR

#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "DetourNavMesh.h"
#include "DetourNavMeshBuilder.h"
#include "DetourNavMeshQuery.h"
#include "Recast.h"

#ifndef CHAYA_NAVMESH_VERSION
#define CHAYA_NAVMESH_VERSION "unknown"
#endif
#ifndef RECASTNAVIGATION_VERSION
#define RECASTNAVIGATION_VERSION "unknown"
#endif

namespace {

enum ExitCode { OK = 0, INVALID_ARGUMENTS = 2, INVALID_GEOMETRY = 3, NO_WALKABLE_SURFACE = 4, NAVMESH_BUILD_FAILED = 5,
                PATH_NOT_FOUND = 6, IO_ERROR = 7 };

const char* statusName(int code) {
    switch (code) {
        case OK: return "OK";
        case INVALID_ARGUMENTS: return "INVALID_ARGUMENTS";
        case INVALID_GEOMETRY: return "INVALID_GEOMETRY";
        case NO_WALKABLE_SURFACE: return "NO_WALKABLE_SURFACE";
        case NAVMESH_BUILD_FAILED: return "NAVMESH_BUILD_FAILED";
        case PATH_NOT_FOUND: return "PATH_NOT_FOUND";
        default: return "IO_ERROR";
    }
}

const unsigned short POLYFLAG_WALK = 0x01;

// ---- small JSON writer ------------------------------------------------------------------------------------------

std::string fmt(const char* f, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof(buf), f, ap);
    va_end(ap);
    return std::string(buf);
}

std::string jstr(const std::string& s) {
    std::string out = "\"";
    for (size_t i = 0; i < s.size(); ++i) {
        const char c = s[i];
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c == '\n') out += "\\n";
        else if ((unsigned char)c < 0x20) out += fmt("\\u%04x", (unsigned char)c);
        else out += c;
    }
    return out + "\"";
}

std::string jnum(double v) { return fmt("%.6f", v); }

std::string jvec(const float* v) { return "[" + jnum(v[0]) + "," + jnum(v[1]) + "," + jnum(v[2]) + "]"; }

bool writeFile(const std::string& path, const std::string& text) {
    FILE* fp = fopen(path.c_str(), "wb");
    if (!fp) return false;
    const bool ok = fwrite(text.data(), 1, text.size(), fp) == text.size();
    return fclose(fp) == 0 && ok;
}

// ---- Recast log capture -----------------------------------------------------------------------------------------

class LogContext : public rcContext {
public:
    std::vector<std::string> errors;
protected:
    virtual void doLog(const rcLogCategory category, const char* msg, const int len) {
        std::string line(msg, len);
        fprintf(stderr, "recast[%d]: %s\n", (int)category, line.c_str());
        if (category == RC_LOG_ERROR) errors.push_back(line);
    }
};

// ---- arguments --------------------------------------------------------------------------------------------------

struct Args {
    std::vector<std::string> v;
    int find(const std::string& name) const {
        for (size_t i = 0; i < v.size(); ++i) if (v[i] == name) return (int)i;
        return -1;
    }
    bool str(const std::string& name, std::string& out) const {
        const int i = find(name);
        if (i < 0 || i + 1 >= (int)v.size()) return false;
        out = v[i + 1];
        return true;
    }
    bool num(const std::string& name, double& out) const {
        std::string s;
        if (!str(name, s)) return false;
        char* end = 0;
        out = strtod(s.c_str(), &end);
        return end && *end == '\0' && std::isfinite(out);
    }
    bool vec3(const std::string& name, float* out) const {
        const int i = find(name);
        if (i < 0 || i + 3 >= (int)v.size()) return false;
        for (int k = 0; k < 3; ++k) {
            char* end = 0;
            const double d = strtod(v[i + 1 + k].c_str(), &end);
            if (!end || *end != '\0' || !std::isfinite(d)) return false;
            out[k] = (float)d;
        }
        return true;
    }
};

// ---- geometry ---------------------------------------------------------------------------------------------------

struct Geometry {
    std::vector<float> verts;
    std::vector<int> tris;
    std::vector<unsigned char> obstacle;  // per triangle
};

// Returns an empty string on success, otherwise why the geometry is invalid.
std::string readObj(const std::string& path, Geometry& g, bool& ioError) {
    ioError = false;
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) { ioError = true; return "cannot open " + path; }
    std::string content;
    char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), fp)) > 0) content.append(buf, n);
    fclose(fp);

    bool inObstacle = false;
    size_t pos = 0;
    int lineNo = 0;
    while (pos < content.size()) {
        size_t eol = content.find('\n', pos);
        if (eol == std::string::npos) eol = content.size();
        std::string line = content.substr(pos, eol - pos);
        pos = eol + 1;
        ++lineNo;
        if (!line.empty() && line[line.size() - 1] == '\r') line.erase(line.size() - 1);
        if (line.size() < 2) continue;
        if ((line[0] == 'g' || line[0] == 'o') && line[1] == ' ') {
            const std::string name = line.substr(2);
            inObstacle = name.compare(0, 8, "obstacle") == 0;
        } else if (line[0] == 'v' && line[1] == ' ') {
            float x, y, z;
            if (sscanf(line.c_str() + 2, "%f %f %f", &x, &y, &z) != 3)
                return fmt("line %d: malformed vertex", lineNo);
            if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z))
                return fmt("line %d: non-finite vertex coordinate", lineNo);
            g.verts.push_back(x); g.verts.push_back(y); g.verts.push_back(z);
        } else if (line[0] == 'f' && line[1] == ' ') {
            std::vector<int> idx;
            const char* p = line.c_str() + 2;
            while (*p) {
                while (*p == ' ' || *p == '\t') ++p;
                if (!*p) break;
                char* end = 0;
                long vi = strtol(p, &end, 10);
                if (end == p) return fmt("line %d: malformed face", lineNo);
                const int nv = (int)(g.verts.size() / 3);
                if (vi < 0) vi = nv + vi + 1;  // OBJ relative index
                if (vi < 1 || vi > nv) return fmt("line %d: face references vertex %ld of %d", lineNo, vi, nv);
                idx.push_back((int)vi - 1);
                p = end;
                while (*p && *p != ' ' && *p != '\t') ++p;  // skip /vt/vn
            }
            if (idx.size() < 3) return fmt("line %d: face with fewer than 3 vertices", lineNo);
            for (size_t k = 2; k < idx.size(); ++k) {
                g.tris.push_back(idx[0]); g.tris.push_back(idx[k - 1]); g.tris.push_back(idx[k]);
                g.obstacle.push_back(inObstacle ? 1 : 0);
            }
        }
    }
    if (g.tris.empty()) return "the geometry has no triangles";
    return "";
}

// ---- bake -------------------------------------------------------------------------------------------------------

struct MetreConfig {
    double cellSize, cellHeight, agentHeight, agentRadius, agentMaxClimb, agentMaxSlopeDeg, regionMinArea, regionMergeArea,
        edgeMaxLen, edgeMaxError, vertsPerPoly, detailSampleDist, detailSampleMaxError;
};

struct Report {
    std::string status = "OK";
    std::string message;
    std::vector<std::string> sections;  // extra top-level "key": value pairs
    void add(const std::string& key, const std::string& json) { sections.push_back(jstr(key) + ":" + json); }
    std::string json() const {
        std::string s = "{" + jstr("tool") + ":" + jstr("chaya-navmesh") + "," + jstr("tool_version") + ":" + jstr(CHAYA_NAVMESH_VERSION)
            + "," + jstr("recastnavigation_version") + ":" + jstr(RECASTNAVIGATION_VERSION) + "," + jstr("status") + ":" + jstr(status);
        if (!message.empty()) s += "," + jstr("message") + ":" + jstr(message);
        for (size_t i = 0; i < sections.size(); ++i) s += "," + sections[i];
        return s + "}\n";
    }
};

int finish(Report& r, const std::string& reportPath, int code, const std::string& message) {
    r.status = statusName(code);
    r.message = message;
    if (!message.empty()) fprintf(stderr, "chaya-navmesh: %s: %s\n", r.status.c_str(), message.c_str());
    if (!reportPath.empty() && !writeFile(reportPath, r.json())) {
        fprintf(stderr, "chaya-navmesh: cannot write report %s\n", reportPath.c_str());
        return IO_ERROR;
    }
    return code;
}

int bake(const Args& a) {
    std::string input, navmeshPath, reportPath;
    a.str("--report", reportPath);
    Report r;
    if (!a.str("--input", input) || !a.str("--navmesh", navmeshPath) || reportPath.empty())
        return finish(r, reportPath, INVALID_ARGUMENTS, "bake needs --input, --navmesh and --report");

    MetreConfig m;
    struct Flag { const char* name; double* dst; } flags[] = {
        {"--cell-size-m", &m.cellSize}, {"--cell-height-m", &m.cellHeight}, {"--agent-height-m", &m.agentHeight},
        {"--agent-radius-m", &m.agentRadius}, {"--agent-max-climb-m", &m.agentMaxClimb},
        {"--agent-max-slope-deg", &m.agentMaxSlopeDeg}, {"--region-min-area-m2", &m.regionMinArea},
        {"--region-merge-area-m2", &m.regionMergeArea}, {"--edge-max-len-m", &m.edgeMaxLen},
        {"--edge-max-error-m", &m.edgeMaxError}, {"--verts-per-poly", &m.vertsPerPoly},
        {"--detail-sample-dist-m", &m.detailSampleDist}, {"--detail-sample-max-error-m", &m.detailSampleMaxError},
    };
    std::string configJson = "{";
    for (size_t i = 0; i < sizeof(flags) / sizeof(flags[0]); ++i) {
        // No defaults: every value is supplied by the caller, in metres (or degrees / a count where named so).
        if (!a.num(flags[i].name, *flags[i].dst))
            return finish(r, reportPath, INVALID_ARGUMENTS, std::string("missing or non-numeric ") + flags[i].name);
        configJson += std::string(i ? "," : "") + jstr(flags[i].name + 2) + ":" + jnum(*flags[i].dst);
    }
    r.add("config_metres", configJson + "}");
    if (m.cellSize <= 0 || m.cellHeight <= 0 || m.agentHeight <= 0 || m.agentRadius < 0 || m.agentMaxClimb < 0
        || m.agentMaxSlopeDeg <= 0 || m.agentMaxSlopeDeg >= 90 || m.regionMinArea < 0 || m.regionMergeArea < 0
        || m.edgeMaxLen < 0 || m.edgeMaxError < 0 || m.detailSampleDist < 0 || m.detailSampleMaxError < 0
        || m.vertsPerPoly < 3 || m.vertsPerPoly > DT_VERTS_PER_POLYGON || m.vertsPerPoly != std::floor(m.vertsPerPoly))
        return finish(r, reportPath, INVALID_ARGUMENTS, "a configuration value is out of range");

    Geometry g;
    bool ioError = false;
    const std::string geomError = readObj(input, g, ioError);
    if (!geomError.empty()) return finish(r, reportPath, ioError ? IO_ERROR : INVALID_GEOMETRY, geomError);
    const int nverts = (int)(g.verts.size() / 3);
    const int ntris = (int)(g.tris.size() / 3);

    float bmin[3], bmax[3];
    rcCalcBounds(&g.verts[0], nverts, bmin, bmax);
    int obstacleTris = 0;
    for (int i = 0; i < ntris; ++i) obstacleTris += g.obstacle[i];
    r.add("input", "{" + jstr("vertices") + ":" + fmt("%d", nverts) + "," + jstr("triangles") + ":" + fmt("%d", ntris) + ","
        + jstr("obstacle_triangles") + ":" + fmt("%d", obstacleTris) + "," + jstr("bounds_min") + ":" + jvec(bmin) + ","
        + jstr("bounds_max") + ":" + jvec(bmax) + "}");
    if (bmax[0] - bmin[0] <= 0 || bmax[2] - bmin[2] <= 0)
        return finish(r, reportPath, INVALID_GEOMETRY, "the geometry has no horizontal extent");

    // Metre-denominated settings -> Recast's rcConfig. The voxel conversions follow RecastDemo's Sample_SoloMesh.
    rcConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.cs = (float)m.cellSize;
    cfg.ch = (float)m.cellHeight;
    cfg.walkableSlopeAngle = (float)m.agentMaxSlopeDeg;
    cfg.walkableHeight = (int)std::ceil(m.agentHeight / m.cellHeight);
    cfg.walkableClimb = (int)std::floor(m.agentMaxClimb / m.cellHeight);
    cfg.walkableRadius = (int)std::ceil(m.agentRadius / m.cellSize);
    cfg.maxEdgeLen = (int)(m.edgeMaxLen / m.cellSize);
    cfg.maxSimplificationError = (float)(m.edgeMaxError / m.cellSize);
    cfg.minRegionArea = (int)std::ceil(m.regionMinArea / (m.cellSize * m.cellSize));
    cfg.mergeRegionArea = (int)std::ceil(m.regionMergeArea / (m.cellSize * m.cellSize));
    cfg.maxVertsPerPoly = (int)m.vertsPerPoly;
    cfg.detailSampleDist = (float)m.detailSampleDist;
    cfg.detailSampleMaxError = (float)m.detailSampleMaxError;
    rcVcopy(cfg.bmin, bmin);
    rcVcopy(cfg.bmax, bmax);
    rcCalcGridSize(cfg.bmin, cfg.bmax, cfg.cs, &cfg.width, &cfg.height);
    r.add("config_voxels", "{" + jstr("cs") + ":" + jnum(cfg.cs) + "," + jstr("ch") + ":" + jnum(cfg.ch) + ","
        + jstr("walkableSlopeAngle") + ":" + jnum(cfg.walkableSlopeAngle) + "," + jstr("walkableHeight") + ":" + fmt("%d", cfg.walkableHeight) + ","
        + jstr("walkableClimb") + ":" + fmt("%d", cfg.walkableClimb) + "," + jstr("walkableRadius") + ":" + fmt("%d", cfg.walkableRadius) + ","
        + jstr("maxEdgeLen") + ":" + fmt("%d", cfg.maxEdgeLen) + "," + jstr("maxSimplificationError") + ":" + jnum(cfg.maxSimplificationError) + ","
        + jstr("minRegionArea") + ":" + fmt("%d", cfg.minRegionArea) + "," + jstr("mergeRegionArea") + ":" + fmt("%d", cfg.mergeRegionArea) + ","
        + jstr("maxVertsPerPoly") + ":" + fmt("%d", cfg.maxVertsPerPoly) + "," + jstr("detailSampleDist") + ":" + jnum(cfg.detailSampleDist) + ","
        + jstr("detailSampleMaxError") + ":" + jnum(cfg.detailSampleMaxError) + "," + jstr("width") + ":" + fmt("%d", cfg.width) + ","
        + jstr("height") + ":" + fmt("%d", cfg.height) + "}");
    if (cfg.walkableHeight < 3 || cfg.walkableClimb < 0)
        return finish(r, reportPath, INVALID_ARGUMENTS, "agent height must span at least 3 cell heights");
    if ((double)cfg.width * (double)cfg.height > 64.0e6)
        return finish(r, reportPath, NAVMESH_BUILD_FAILED,
                      fmt("a %d x %d cell grid is too large for one navmesh tile; increase cell size", cfg.width, cfg.height));

    LogContext ctx;
    std::string stages = "{";

    // 1. Walkable surface filtering: Recast's slope test, then blocked geometry forced solid-but-unwalkable.
    std::vector<unsigned char> areas(ntris, 0);
    rcMarkWalkableTriangles(&ctx, cfg.walkableSlopeAngle, &g.verts[0], nverts, &g.tris[0], ntris, &areas[0]);
    int walkableTris = 0;
    for (int i = 0; i < ntris; ++i) {
        if (g.obstacle[i]) areas[i] = RC_NULL_AREA;
        if (areas[i] != RC_NULL_AREA) ++walkableTris;
    }
    stages += jstr("walkable_triangles") + ":" + fmt("%d", walkableTris);
    if (walkableTris == 0) {
        r.add("stages", stages + "}");
        return finish(r, reportPath, NO_WALKABLE_SURFACE, "no triangle is within the agent's maximum slope");
    }

    // 2. Voxelise into a heightfield and filter spans the agent cannot use (climb, ledges, head clearance).
    rcHeightfield* solid = rcAllocHeightfield();
    if (!solid || !rcCreateHeightfield(&ctx, *solid, cfg.width, cfg.height, cfg.bmin, cfg.bmax, cfg.cs, cfg.ch))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not create the heightfield");
    if (!rcRasterizeTriangles(&ctx, &g.verts[0], nverts, &g.tris[0], &areas[0], ntris, *solid, cfg.walkableClimb))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not rasterise the triangles");
    rcFilterLowHangingWalkableObstacles(&ctx, cfg.walkableClimb, *solid);
    rcFilterLedgeSpans(&ctx, cfg.walkableHeight, cfg.walkableClimb, *solid);
    rcFilterWalkableLowHeightSpans(&ctx, cfg.walkableHeight, *solid);

    // 3. Compact heightfield, eroded by the agent radius.
    rcCompactHeightfield* chf = rcAllocCompactHeightfield();
    if (!chf || !rcBuildCompactHeightfield(&ctx, cfg.walkableHeight, cfg.walkableClimb, *solid, *chf))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not build the compact heightfield");
    rcFreeHeightField(solid);
    if (!rcErodeWalkableArea(&ctx, cfg.walkableRadius, *chf))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not erode the walkable area");
    int walkableSpans = 0;
    for (int i = 0; i < chf->spanCount; ++i) if (chf->areas[i] != RC_NULL_AREA) ++walkableSpans;
    stages += "," + jstr("compact_spans") + ":" + fmt("%d", chf->spanCount) + "," + jstr("walkable_spans_after_erosion") + ":" + fmt("%d", walkableSpans);
    if (walkableSpans == 0) {
        r.add("stages", stages + "}");
        return finish(r, reportPath, NO_WALKABLE_SURFACE, "nothing is walkable once agent height, climb and radius are applied");
    }

    // 4. Watershed regions.
    if (!rcBuildDistanceField(&ctx, *chf) || !rcBuildRegions(&ctx, *chf, 0, cfg.minRegionArea, cfg.mergeRegionArea))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not build regions");
    stages += "," + jstr("regions") + ":" + fmt("%d", (int)chf->maxRegions);

    // 5. Contours.
    rcContourSet* cset = rcAllocContourSet();
    if (!cset || !rcBuildContours(&ctx, *chf, cfg.maxSimplificationError, cfg.maxEdgeLen, *cset))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not build contours");
    stages += "," + jstr("contours") + ":" + fmt("%d", cset->nconts);

    // 6. Polygons, and the detail mesh Detour needs for heights.
    rcPolyMesh* pmesh = rcAllocPolyMesh();
    if (!pmesh || !rcBuildPolyMesh(&ctx, *cset, cfg.maxVertsPerPoly, *pmesh))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not build the polygon mesh");
    rcPolyMeshDetail* dmesh = rcAllocPolyMeshDetail();
    if (!dmesh || !rcBuildPolyMeshDetail(&ctx, *pmesh, *chf, cfg.detailSampleDist, cfg.detailSampleMaxError, *dmesh))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "could not build the detail mesh");
    rcFreeCompactHeightfield(chf);
    rcFreeContourSet(cset);
    stages += "," + jstr("polymesh_vertices") + ":" + fmt("%d", pmesh->nverts) + "," + jstr("polygons") + ":" + fmt("%d", pmesh->npolys)
        + "," + jstr("detail_triangles") + ":" + fmt("%d", dmesh->ntris) + "}";
    r.add("stages", stages);
    if (pmesh->npolys == 0)
        return finish(r, reportPath, NO_WALKABLE_SURFACE, "Recast produced no polygons (every region was below the minimum area)");
    if (pmesh->nverts >= 0xffff)
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "too many vertices for one Detour tile");

    // 7. Detour navmesh data (Detour's own serialised tile format).
    for (int i = 0; i < pmesh->npolys; ++i) pmesh->flags[i] = pmesh->areas[i] == RC_WALKABLE_AREA ? POLYFLAG_WALK : 0;
    dtNavMeshCreateParams params;
    memset(&params, 0, sizeof(params));
    params.verts = pmesh->verts;
    params.vertCount = pmesh->nverts;
    params.polys = pmesh->polys;
    params.polyAreas = pmesh->areas;
    params.polyFlags = pmesh->flags;
    params.polyCount = pmesh->npolys;
    params.nvp = pmesh->nvp;
    params.detailMeshes = dmesh->meshes;
    params.detailVerts = dmesh->verts;
    params.detailVertsCount = dmesh->nverts;
    params.detailTris = dmesh->tris;
    params.detailTriCount = dmesh->ntris;
    params.walkableHeight = (float)m.agentHeight;
    params.walkableRadius = (float)m.agentRadius;
    params.walkableClimb = (float)m.agentMaxClimb;
    rcVcopy(params.bmin, pmesh->bmin);
    rcVcopy(params.bmax, pmesh->bmax);
    params.cs = cfg.cs;
    params.ch = cfg.ch;
    params.buildBvTree = true;
    unsigned char* navData = 0;
    int navDataSize = 0;
    if (!dtCreateNavMeshData(&params, &navData, &navDataSize))
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "Detour could not create navmesh data");
    rcFreePolyMesh(pmesh);
    rcFreePolyMeshDetail(dmesh);

    FILE* fp = fopen(navmeshPath.c_str(), "wb");
    if (!fp || fwrite(navData, 1, navDataSize, fp) != (size_t)navDataSize || fclose(fp) != 0) {
        dtFree(navData);
        return finish(r, reportPath, IO_ERROR, "cannot write " + navmeshPath);
    }

    // Read the polygons back out of the initialised dtNavMesh -- what a Detour query will actually traverse.
    dtNavMesh* nav = dtAllocNavMesh();
    if (!nav || dtStatusFailed(nav->init(navData, navDataSize, DT_TILE_FREE_DATA))) {
        dtFree(navData);
        return finish(r, reportPath, NAVMESH_BUILD_FAILED, "Detour could not load the navmesh it just built");
    }
    const dtNavMesh* cnav = nav;
    const dtMeshTile* tile = cnav->getTile(0);
    std::string polys = "[";
    for (int ip = 0; ip < tile->header->polyCount; ++ip) {
        const dtPoly* p = &tile->polys[ip];
        if (p->getType() != DT_POLYTYPE_GROUND) continue;
        std::string vs = "[";
        for (int k = 0; k < p->vertCount; ++k) vs += std::string(k ? "," : "") + jvec(&tile->verts[p->verts[k] * 3]);
        std::string links = "[";
        bool first = true;
        for (unsigned int li = p->firstLink; li != DT_NULL_LINK; li = tile->links[li].next) {
            const dtLink& link = tile->links[li];
            const dtMeshTile* nt = 0;
            const dtPoly* np = 0;
            if (dtStatusFailed(cnav->getTileAndPolyByRef(link.ref, &nt, &np))) continue;
            const float* va = &tile->verts[p->verts[link.edge] * 3];
            const float* vb = &tile->verts[p->verts[(link.edge + 1) % p->vertCount] * 3];
            links += std::string(first ? "" : ",") + "{" + jstr("neighbor") + ":" + fmt("%d", (int)(np - nt->polys)) + ","
                + jstr("edge") + ":" + fmt("%d", (int)link.edge) + "," + jstr("portal") + ":[" + jvec(va) + "," + jvec(vb) + "]}";
            first = false;
        }
        polys += std::string(ip ? "," : "") + "{" + jstr("id") + ":" + fmt("%d", ip) + "," + jstr("ref") + ":"
            + fmt("%u", (unsigned int)(cnav->getPolyRefBase(tile) | (dtPolyRef)ip)) + "," + jstr("area") + ":" + fmt("%d", (int)p->getArea())
            + "," + jstr("flags") + ":" + fmt("%d", (int)p->flags) + "," + jstr("vertices") + ":" + vs + "]," + jstr("links") + ":" + links + "]}";
    }
    r.add("navmesh", "{" + jstr("bytes") + ":" + fmt("%d", navDataSize) + "," + jstr("polygon_count") + ":" + fmt("%d", tile->header->polyCount)
        + "," + jstr("bounds_min") + ":" + jvec(tile->header->bmin) + "," + jstr("bounds_max") + ":" + jvec(tile->header->bmax) + "}");
    r.add("polygons", polys + "]");
    dtFreeNavMesh(nav);
    return finish(r, reportPath, OK, "");
}

// ---- path -------------------------------------------------------------------------------------------------------

int path(const Args& a) {
    std::string navmeshPath, outputPath;
    a.str("--output", outputPath);
    Report r;
    float start[3], end[3], ext[3];
    if (!a.str("--navmesh", navmeshPath) || outputPath.empty() || !a.vec3("--start", start) || !a.vec3("--end", end)
        || !a.vec3("--half-extents", ext))
        return finish(r, outputPath, INVALID_ARGUMENTS, "path needs --navmesh, --output, --start, --end and --half-extents");

    FILE* fp = fopen(navmeshPath.c_str(), "rb");
    if (!fp) return finish(r, outputPath, IO_ERROR, "cannot open " + navmeshPath);
    fseek(fp, 0, SEEK_END);
    const long size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    unsigned char* data = size > 0 ? (unsigned char*)dtAlloc(size, DT_ALLOC_PERM) : 0;
    const bool readOk = data && fread(data, 1, size, fp) == (size_t)size;
    fclose(fp);
    if (!readOk) { if (data) dtFree(data); return finish(r, outputPath, IO_ERROR, "cannot read " + navmeshPath); }

    dtNavMesh* nav = dtAllocNavMesh();
    if (!nav || dtStatusFailed(nav->init(data, (int)size, DT_TILE_FREE_DATA))) {
        dtFree(data);
        return finish(r, outputPath, INVALID_GEOMETRY, "not a Detour navmesh tile: " + navmeshPath);
    }
    dtNavMeshQuery* query = dtAllocNavMeshQuery();
    if (!query || dtStatusFailed(query->init(nav, 4096)))
        return finish(r, outputPath, NAVMESH_BUILD_FAILED, "could not initialise the Detour query");
    dtQueryFilter filter;
    filter.setIncludeFlags(POLYFLAG_WALK);
    filter.setExcludeFlags(0);

    dtPolyRef startRef = 0, endRef = 0;
    float startPt[3], endPt[3];
    query->findNearestPoly(start, ext, &filter, &startRef, startPt);
    query->findNearestPoly(end, ext, &filter, &endRef, endPt);
    r.add("start_ref", fmt("%u", (unsigned int)startRef));
    r.add("end_ref", fmt("%u", (unsigned int)endRef));
    if (!startRef) return finish(r, outputPath, PATH_NOT_FOUND, "the start is not within the search extents of the navmesh");
    if (!endRef) return finish(r, outputPath, PATH_NOT_FOUND, "the end is not within the search extents of the navmesh");
    r.add("start_on_navmesh", jvec(startPt));
    r.add("end_on_navmesh", jvec(endPt));

    const int MAX_POLYS = 4096;
    std::vector<dtPolyRef> polys(MAX_POLYS);
    int npolys = 0;
    const dtStatus st = query->findPath(startRef, endRef, startPt, endPt, &filter, &polys[0], &npolys, MAX_POLYS);
    if (dtStatusFailed(st) || npolys == 0)
        return finish(r, outputPath, PATH_NOT_FOUND, "Detour found no path");
    std::string corridor = "[";
    const dtMeshTile* tile = 0;
    const dtPoly* poly = 0;
    for (int i = 0; i < npolys; ++i) {
        nav->getTileAndPolyByRef(polys[i], &tile, &poly);
        corridor += std::string(i ? "," : "") + fmt("%d", (int)(poly - tile->polys));
    }
    r.add("corridor", corridor + "]");
    if (polys[npolys - 1] != endRef || dtStatusDetail(st, DT_PARTIAL_RESULT))
        return finish(r, outputPath, PATH_NOT_FOUND, "the start and end are on disconnected parts of the navmesh");

    std::vector<float> straight(MAX_POLYS * 3);
    int nstraight = 0;
    if (dtStatusFailed(query->findStraightPath(startPt, endPt, &polys[0], npolys, &straight[0], 0, 0, &nstraight, MAX_POLYS)) || nstraight == 0)
        return finish(r, outputPath, PATH_NOT_FOUND, "Detour could not string-pull the corridor");
    std::string pts = "[";
    for (int i = 0; i < nstraight; ++i) pts += std::string(i ? "," : "") + jvec(&straight[i * 3]);
    r.add("straight_path", pts + "]");
    dtFreeNavMeshQuery(query);
    dtFreeNavMesh(nav);
    return finish(r, outputPath, OK, "");
}

}  // namespace

int main(int argc, char** argv) {
    Args a;
    for (int i = 2; i < argc; ++i) a.v.push_back(argv[i]);
    const std::string cmd = argc > 1 ? argv[1] : "";
    if (cmd == "--version" || cmd == "version") {
        printf("chaya-navmesh %s recastnavigation %s\n", CHAYA_NAVMESH_VERSION, RECASTNAVIGATION_VERSION);
        return OK;
    }
    if (cmd == "bake") return bake(a);
    if (cmd == "path") return path(a);
    fprintf(stderr, "usage: chaya-navmesh --version | bake ... | path ...  (see the header of services/reconstruction/native/chaya-navmesh/src/main.cpp)\n");
    return INVALID_ARGUMENTS;
}
