package dotty.tools.dotc.util

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import org.junit.Assert.*
import org.junit.Test

class Md5Test:

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  @Test def rfc1321TestSuite(): Unit =
    // The test suite in appendix A.5 of RFC 1321
    val expected = List(
      "" -> "d41d8cd98f00b204e9800998ecf8427e",
      "a" -> "0cc175b9c0f1b6a831c399e269772661",
      "abc" -> "900150983cd24fb0d6963f7d28e17f72",
      "message digest" -> "f96b697d7cb7938d525a2f31aaf161d0",
      "abcdefghijklmnopqrstuvwxyz" -> "c3fcd3d76192e4007dfb496cca67e13b",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" -> "d174ab98d277d9f5a5611c2c9f419d9f",
      "1234567890" * 8 -> "57edf4a22be3c955ac49da2e2107b67a")
    for (input, digest) <- expected do
      assertEquals(input, digest, hex(Md5.digest(input.getBytes(UTF_8))))

  @Test def sinesAreTheIntegerPartsOfSines(): Unit =
    for i <- 0 until 64 do
      assertEquals(i.toString, (math.abs(math.sin(i + 1)) * 4294967296.0).toLong.toInt, Md5.sines(i))

  @Test def matchesMessageDigestAcrossBlockBoundaries(): Unit =
    val random = new scala.util.Random(42)
    for length <- (0 to 200) ++ List(1000, 4096, 65537) do
      val input = new Array[Byte](length)
      random.nextBytes(input)
      val jdk = MessageDigest.getInstance("MD5").digest(input)
      assertEquals(s"length $length", hex(jdk), hex(Md5.digest(input)))
