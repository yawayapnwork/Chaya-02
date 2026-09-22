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
    getSplatCount(): number;
  }

  export class DropInViewer extends Viewer {}

  export const SceneFormat: { Ply: number; Splat: number; KSplat: number; Spz: number };
  export const LogLevel: { None: number; Error: number; Warning: number; Info: number; Debug: number };
  export const RenderMode: { Always: number; OnChange: number; Never: number };
  export const SceneRevealMode: { Default: number; Gradual: number; Instant: number };
  export const WebXRMode: { None: number; VR: number; AR: number };
}
