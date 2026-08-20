// Native BarcodeDetector API types (Chrome 83+, Edge 83+)
// https://developer.mozilla.org/en-US/docs/Web/API/Barcode_Detection_API

type BarcodeFormat =
  | 'aztec' | 'code_128' | 'code_39' | 'code_93'
  | 'codabar' | 'data_matrix' | 'ean_13' | 'ean_8'
  | 'itf' | 'pdf417' | 'qr_code' | 'upc_a' | 'upc_e'
  | 'unknown';

interface DetectedBarcode {
  readonly rawValue: string;
  readonly format: BarcodeFormat;
  readonly boundingBox: DOMRectReadOnly;
  readonly cornerPoints: ReadonlyArray<{ x: number; y: number }>;
}

interface BarcodeDetectorOptions {
  formats?: BarcodeFormat[];
}

declare class BarcodeDetector {
  constructor(options?: BarcodeDetectorOptions);
  detect(image: ImageBitmapSource): Promise<DetectedBarcode[]>;
  static getSupportedFormats(): Promise<BarcodeFormat[]>;
}
