package dotty.tools.dotc

import dotty.tools.dotc.config.PlatformHooks

/** Main class of `snc`, the native compiler with the Scala Native compiler
 *  plugin built in and enabled, and Scala Native's junit-plugin built in. See
 *  README.md for its command line.
 */
object SncMain:
  def main(args: Array[String]): Unit =
    PlatformHooks.registerPlugin("scala.scalanative.nscplugin.ScalaNativePlugin",
      () => new scala.scalanative.nscplugin.ScalaNativePlugin, enabledByDefault = true)
    PlatformHooks.registerMiniPhaseHooks(NscPluginMiniPhaseHooks.table)
    // Selected by an -Xplugin path to its jar, as build tools add it for JUnit tests
    PlatformHooks.registerPlugin("scala.scalanative.junit.plugin.ScalaNativeJUnitPlugin",
      () => new scala.scalanative.junit.plugin.ScalaNativeJUnitPlugin)
    sys.exit(SncDriver.run(args))
