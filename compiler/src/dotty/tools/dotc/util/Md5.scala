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
    // The blocks of the input are read in place; the rest of it, a 1 bit,
    // zeros and the bit length (little-endian) make one or two more blocks
    val length = input.length
    val whole = length - length % 64
    val rest = length - whole
    val tail = new Array[Byte](if rest + 9 <= 64 then 64 else 128)
    System.arraycopy(input, whole, tail, 0, rest)
    tail(rest) = 0x80.toByte
    val bitLength = length.toLong * 8
    var i = 0
    while i < 8 do
      tail(tail.length - 8 + i) = (bitLength >>> (8 * i)).toByte
      i += 1

    val state = Array(0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476)
    val words = new Array[Int](16)
    var block = 0
    while block < whole do
      compress(state, words, input, block)
      block += 64
    block = 0
    while block < tail.length do
      compress(state, words, tail, block)
      block += 64

    val result = new Array[Byte](16)
    i = 0
    while i < 16 do
      result(i) = (state(i / 4) >>> (8 * (i % 4))).toByte
      i += 1
    result

  /** Adds the 64-byte block of `data` at `offset` to `state`; `words` is
   *  where the block's words go.
   */
  private def compress(state: Array[Int], words: Array[Int], data: Array[Byte], offset: Int): Unit =
    var j = 0
    while j < 16 do
      val at = offset + 4 * j
      words(j) = (data(at) & 0xff) | (data(at + 1) & 0xff) << 8 |
        (data(at + 2) & 0xff) << 16 | (data(at + 3) & 0xff) << 24
      j += 1
    var a = state(0)
    var b = state(1)
    var c = state(2)
    var d = state(3)
    var step = 0
    while step < 64 do
      var f = 0
      var word = 0
      if step < 16 then
        f = (b & c) | (~b & d)
        word = step
      else if step < 32 then
        f = (d & b) | (~d & c)
        word = (5 * step + 1) % 16
      else if step < 48 then
        f = b ^ c ^ d
        word = (3 * step + 5) % 16
      else
        f = c ^ (b | ~d)
        word = (7 * step) % 16
      val rotated = Integer.rotateLeft(a + f + sines(step) + words(word), shifts(step))
      a = d
      d = c
      c = b
      b = b + rotated
      step += 1
    state(0) += a
    state(1) += b
    state(2) += c
    state(3) += d
