package dotty.tools.dotc.util

/** The MD5 message digest, as RFC 1321 defines it. The Scala Native build of
 *  the compiler uses it, for want of `java.security.MessageDigest`.
 */
object Md5:

  /** Per-round shift amounts. */
  private val shifts: Array[Int] = Array(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21)

  /** The integer part of `abs(sin(i + 1)) * 2^32`, for each step `i`. */
  private[util] val sines: Array[Int] = Array(
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391)

  /** The 16-byte digest of `input`. */
  def digest(input: Array[Byte]): Array[Byte] =
    // Append a 1 bit, zeros, and the bit length (little-endian), to a multiple of 64 bytes
    val length = input.length
    val padded = java.util.Arrays.copyOf(input, ((length + 8) / 64 + 1) * 64).nn
    padded(length) = 0x80.toByte
    val bitLength = length.toLong * 8
    for i <- 0 until 8 do
      padded(padded.length - 8 + i) = (bitLength >>> (8 * i)).toByte

    var a0 = 0x67452301
    var b0 = 0xefcdab89
    var c0 = 0x98badcfe
    var d0 = 0x10325476
    val words = new Array[Int](16)
    var block = 0
    while block < padded.length do
      for j <- 0 until 16 do
        val at = block + 4 * j
        words(j) = (padded(at) & 0xff) | (padded(at + 1) & 0xff) << 8 |
          (padded(at + 2) & 0xff) << 16 | (padded(at + 3) & 0xff) << 24
      var a = a0
      var b = b0
      var c = c0
      var d = d0
      for step <- 0 until 64 do
        val (f, word) =
          if step < 16 then ((b & c) | (~b & d), step)
          else if step < 32 then ((d & b) | (~d & c), (5 * step + 1) % 16)
          else if step < 48 then (b ^ c ^ d, (3 * step + 5) % 16)
          else (c ^ (b | ~d), (7 * step) % 16)
        val rotated = Integer.rotateLeft(a + f + sines(step) + words(word), shifts(step))
        a = d
        d = c
        c = b
        b = b + rotated
      a0 += a
      b0 += b
      c0 += c
      d0 += d
      block += 64

    val result = new Array[Byte](16)
    for (word, i) <- Array(a0, b0, c0, d0).zipWithIndex; k <- 0 until 4 do
      result(4 * i + k) = (word >>> (8 * k)).toByte
    result
