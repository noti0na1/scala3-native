import org.junit.Test
import org.junit.Assert.*
class MyTest:
  @Test def addition(): Unit = assertEquals(4, 2 + 2)
  @Test def strings(): Unit = assertTrue("abc".startsWith("a"))

class Lifecycle:
  @org.junit.Before def setUp(): Unit = ()
  @org.junit.After def tearDown(): Unit = ()
  @Test(expected = classOf[IllegalStateException]) def throws(): Unit = throw new IllegalStateException
  @org.junit.Ignore("later") @Test def ignored(): Unit = ()

object Lifecycle:
  @org.junit.BeforeClass def before(): Unit = ()
