package org.specs2
package reporter

import examples.{HelloWorldSpec, HelloWorldUnitSpec}
import mock._
import matcher._
import main.Arguments
import io.StringOutput
import sbt.testing._
import runner._
import specification.core._
import specification.process.DefaultExecutor
import control.runAction

class SbtPrinterSpec(env: Env) extends Specification { def is = s2"""

 Sbt events must be fired when a specification is being executed with the SbtPrinter
   TestEvent: succeed                            $${printer1().e1}
   the duration must be defined                  $${printer1().e2}
   contexts must appear in the name of the event $${printer1().e3}

 A SbtPrinter should
   print the specification title if defined      ${printer2().e1}
   print HelloWorldSpec ok                       $${printer2().e2}

                                                 """
  val factory = fragmentFactory

  case class printer1() extends Mockito { outer =>
    val logger =  mock[Logger]
    val handler = mock[EventHandler]

    val printer = new SbtPrinter {
      lazy val loggers = Array(logger)
      lazy val events = new SbtEvents {
        lazy val handler = outer.handler
        lazy val taskDef = new TaskDef("", Fingerprints.fp1, true, Array())
      }
    }

    def e1 = {
      executeAndPrintHelloWorldUnitSpec
      there was atLeastOne(handler).handle(eventWithStatus(Status.Success))
    }

    def e2 = {
      executeAndPrintHelloWorldUnitSpec
      there was atLeastOne(handler).handle(eventWithDurationGreaterThanOrEqualTo(0))
    }

    def e3 = {
      executeAndPrintHelloWorldUnitSpec
      there was atLeastOne(handler).handle(eventWithNameMatching("HW::The 'Hello world' string should::contain 11 characters"))
    }

    def executeAndPrintHelloWorldUnitSpec = {
      val executed = DefaultExecutor.executeSpecWithoutShutdown((new HelloWorldUnitSpec).is.fragments, env)
      runAction(printer.print(env)(executed))
    }

  }

  case class printer2() extends Mockito { outer =>

    def e1 = {
      runAction(printer.printSpecification(env)(new HelloWorldSpec { override def is = "title".title ^ "\ntext" }))
      eventually(there was one(logger).info(beMatching(".*title.*")))
    }

    def e2 = {
      val executed = DefaultExecutor.executeSpecWithoutShutdown((new HelloWorldSpec).is, env)

      print(executed).replaceAll("""(\d+ seconds?, )?\d+ ms""", "0 ms").replaceAll(" ", "_") ===
      """|HelloWorldSpec
         |
         | This is a specification to check the 'Hello world' string
         |
         | The 'Hello world' string should
         |   + contain 11 characters
         |   + start with 'Hello'
         |   + end with 'world'
         |
         |Total for specification HelloWorldSpec
         |Finished in 0 ms
         |3 examples, 0 failure, 0 error
         |""".stripMargin.replaceAll(" ", "_")
    }

    def print(spec: SpecStructure) = {
      runAction(printer.print(Env(arguments = Arguments("nocolor")))(spec))
      stringLogger.flush()
      stringLogger.messages.mkString("\n")
    }

    val handler = mock[EventHandler]
    val logger = mock[Logger]

    val stringLogger = new Logger with StringOutput {
      def ansiCodesSupported = false
      def warn(msg: String)  { append(msg) }
      def error(msg: String) { append(msg) }
      def debug(msg: String) { append(msg) }
      def trace(t: Throwable){ append(t.getMessage) }
      def info(msg: String)  { append(msg) }
    }

    val printer = new SbtPrinter {
      lazy val loggers: Array[Logger] = Array(logger, stringLogger)
      lazy val events = new SbtEvents {
        lazy val handler = outer.handler
        lazy val taskDef = new TaskDef("", Fingerprints.fp1, true, Array())
      }
    }

  }

  def eventWithStatus(s: Status): Matcher[Event] =
    beTypedEqualTo(s) ^^ ((_: Event).status())

  def eventWithDurationGreaterThanOrEqualTo(d: Long): Matcher[Event] =
    beGreaterThanOrEqualTo(d) ^^ ((_: Event).duration())

  def eventWithNameMatching(n: String): Matcher[Event] =
    beLike[Selector] { case ts: TestSelector => ts.testName must beMatching(n) } ^^ ((_: Event).selector())

}
