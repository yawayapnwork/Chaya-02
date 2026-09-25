// @mkkellogg/gaussian-splats-3d ships as a prebuilt ES module bundle with no TypeScript types (its README
// documents the API as plain JS object options). This covers only what SplatViewerCanvas.tsx actually
// uses; option bags are intentionally loose (matches the library's own untyped, string-keyed API).
declare module "@mkkellogg/gaussian-splats-3d" {
  import * as THREE from "three";

  export interface ViewerOptions {
    selfDrivenMode?: boolean;
    renderer?: THREE.WebGLRenderer;
    camera?: THREE.Camera;
    threeScene?: THREE.Scene;
    useBuiltInControls?: boolean;
    ignoreDevicePixelRatio?: boolean;
    gpuAcceleratedSort?: boolean;
    sharedMemoryForWorkers?: boolean;
    halfPrecisionCovariancesOnGPU?: boolean;
    sphericalHarmonicsDegree?: 0 | 1 | 2;
    logLevel?: number;
    cameraUp?: [number, number, number];
    initialCameraPosition?: [number, number, number];
    initialCameraLookAt?: [number, number, number];
    rootElement?: HTMLElement;
    [key: string]: unknown;
  }

  export interface AddSplatSceneOptions {
    format?: number;
    showLoadingUI?: boolean;
    splatAlphaRemovalThreshold?: number;
    position?: [number, number, number];
    rotation?: [number, number, number, number];
    scale?: [number, number, number];
    progressiveLoad?: boolean;
    onProgress?: (percent: number, percentLabel: string, stage: unknown) => void;
    [key: string]: unknown;
  }

  export class Viewer {
    constructor(options?: ViewerOptions);
    addSplatScene(path: string, options?: AddSplatSceneOptions): Promise<void>;
    update(): void;
    render(): void;
    start(): void;
    dispose(): Promise<void> | void;
    /** 0.4.7 has no Viewer.getSplatCount(); the loaded splat count is on the SplatMesh. */
    getSplatMesh(): SplatMesh;
  }

  export class SplatMesh {
    getSplatCount(): number;
  }

  export class DropInViewer extends Viewer {}

  export const SceneFormat: { Ply: number; Splat: number; KSplat: number; Spz: number };
  export const LogLevel: { None: number; Error: number; Warning: number; Info: number; Debug: number };
  export const RenderMode: { Always: number; OnChange: number; Never: number };
  export const SceneRevealMode: { Default: number; Gradual: number; Instant: number };
  export const WebXRMode: { None: number; VR: number; AR: number };
}

// The ES module build itself, imported by path from lib/ksplat-compat.test.ts: the same file bundlers select through the
// package's `module` field for the viewer (Node's own resolution of the bare specifier would load the UMD build instead).
// Only the loader surface that test exercises is typed. Signatures follow the 0.4.7 source.
declare module "@mkkellogg/gaussian-splats-3d/build/gaussian-splats-3d.module.js" {
  import * as THREE from "three";

  export class SplatBuffer {
    static HeaderSizeBytes: number;
    static SectionHeaderSizeBytes: number;
    compressionLevel: number;
    getSplatCount(): number;
    getSplatCenter(index: number, outCenter: THREE.Vector3): void;
    getSplatScaleAndRotation(index: number, outScale: THREE.Vector3, outRotation: THREE.Quaternion): void;
    getSplatColor(index: number, outColor: THREE.Vector4): void;
  }

  export class KSplatLoader {
    static checkVersion(buffer: ArrayBuffer): boolean;
    static loadFromFileData(fileData: ArrayBuffer): Promise<SplatBuffer>;
  }

  export class PlyLoader {
    static loadFromFileData(plyFileData: ArrayBuffer, minimumAlpha: number, compressionLevel: number,
                            optimizeSplatData: boolean, outSphericalHarmonicsDegree?: number): Promise<SplatBuffer>;
  }
}
