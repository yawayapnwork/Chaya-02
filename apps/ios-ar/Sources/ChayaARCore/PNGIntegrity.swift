import Foundation

/// Structural completeness of a PNG file (the server's anchor target images are PNGs: GET .../anchors/{a}/target.png).
///
/// A reference image must be the exact printed artwork. ImageIO decodes what it can of a truncated file, and for a
/// source created from complete `Data` it may still report `.statusComplete` (the iOS 17.5 Simulator does for a PNG cut in
/// half: ios run 39204149235). So completeness is checked on the file itself, independently of the decoder: the
/// signature, then every chunk's length and CRC-32 in order, ending with IEND exactly at the end of the data.
public enum PNGIntegrity {

    static let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    /// nil when `data` is one complete, well-formed PNG; otherwise why it is not.
    public static func problem(in data: Data) -> String? {
        let bytes = [UInt8](data)
        guard bytes.count >= signature.count, Array(bytes[0..<signature.count]) == signature else {
            return "not a PNG (no PNG signature)"
        }
        var offset = signature.count
        var first = true
        while true {
            guard bytes.count - offset >= 12 else { return "truncated: the data ends before the IEND chunk" }
            let length = Int(bigEndian32(bytes, offset))
            guard length <= 0x7FFF_FFFF, bytes.count - offset - 12 >= length else {
                return "truncated: a chunk is longer than the remaining data"
            }
            let typeRange = (offset + 4)..<(offset + 8)
            let type = String(decoding: bytes[typeRange], as: UTF8.self)
            guard !first || type == "IHDR" else { return "malformed: the first chunk is \(type), not IHDR" }
            first = false
            let crcOffset = offset + 8 + length
            guard crc32(bytes[(offset + 4)..<crcOffset]) == bigEndian32(bytes, crcOffset) else {
                return "corrupt: the CRC of chunk \(type) does not match"
            }
            offset = crcOffset + 4
            if type == "IEND" {
                return offset == bytes.count ? nil : "malformed: data follows the IEND chunk"
            }
        }
    }

    private static func bigEndian32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) << 24 | UInt32(b[i + 1]) << 16 | UInt32(b[i + 2]) << 8 | UInt32(b[i + 3])
    }

    private static let table: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    /// CRC-32 as PNG defines it (ISO 3309 / ITU-T V.42), over the chunk type and data.
    static func crc32(_ bytes: ArraySlice<UInt8>) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in bytes { c = table[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }
}
