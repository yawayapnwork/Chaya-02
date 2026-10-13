// Live counts of what the viewer holds that the browser does not reclaim on its own: WebGL renderers (each owns a GPU
// context, and browsers cap how many a page may have), GaussianSplats3D viewers (sort worker, GPU textures) and object
// URLs for downloaded artifacts (each pins its bytes in memory until revoked). Incremented on allocation, decremented on
// release. Exposed on window so the repeated-switching e2e spec, and anyone diagnosing a leak in a browser console,
// can check that switching models returns them to one scene's worth.

export interface ViewerResources {
  renderers: number;
  viewers: number;
  objectUrls: number;
}

export const viewerResources: ViewerResources = { renderers: 0, viewers: 0, objectUrls: 0 };

if (typeof window !== "undefined") {
  (window as Window & { __chayaViewerResources?: ViewerResources }).__chayaViewerResources = viewerResources;
}
