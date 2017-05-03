package org.specs2
package specification
package process

import specification.core._

import scala.concurrent._
import duration._
import control._
import producer._
import producers._
import Actions._
import fp.syntax._

/**
 * Functions for executing fragments.
 *
 * The default execution model executes all examples concurrently and uses steps as
 * "join" points
 *
 */
trait Executor {

  /**
   * execute fragments:
   *
   *  - filter the ones that the user wants to keep
   *  - sequence the execution so that only parts in between steps are executed concurrently
   */
  def execute(env: Env): AsyncTransducer[Fragment, Fragment]
}

/**
 * Default execution for specifications:
 * 
 *  - concurrent by default
 *  - using steps for synchronisation points
 */
trait DefaultExecutor extends Executor {

  /**
   * execute fragments:
   *
   *  - filter the ones that the user wants to keep
   *  - sequence the execution so that only parts in between steps are executed concurrently
   */
  def execute(env: Env): AsyncTransducer[Fragment, Fragment] = { contents: AsyncStream[Fragment] =>
    execute1(env)(contents).andFinally(protect(env.shutdown))
  }

  /**
   * execute fragments possibly with a recursive call to execute1.
   *
   * The difference with `execute` is that `execute` shuts down the environment when the process is finished
   */
  def execute1(env: Env): AsyncTransducer[Fragment, Fragment] = { contents: AsyncStream[Fragment] =>
    sequencedExecution(env)(contents).flatMap(executeOnline(env))
  }

  /**
   * execute fragments, making sure that:
   *
   *  - "join" points are respected, i.e. when a Fragment is a join we must make sure that all previously
   *    executing fragments have finished their execution
   *
   *  - the fragments execute sequentially when args.sequential
   *
   *  - the execution stops if one fragment indicates that the result of the previous executions is not correct
   */
  def sequencedExecution(env: Env): AsyncTransducer[Fragment, Fragment] = {
    type S = (Vector[Fragment], Vector[Fragment], Option[Fragment])
    val init: S = (Vector.empty, Vector.empty, None)
    val arguments = env.arguments

    val last: S => AsyncStream[Fragment] = {
      case (toStart, _, previousStep) =>
        emit(toStart.toList.map(_.startExecutionAfter(previousStep)(env)))
    }

    transducers.producerState(init, Option(last)) { case (fragment, (previous, previousStarted, previousStep)) =>
      val timeout = env.timeout.orElse(fragment.execution.timeout)

      if (arguments.skipAll)
        (one(if (fragment.isExecutable) fragment.skip else fragment), init)
      else if (arguments.sequential) {
        val started = fragment.startExecutionAfter(previousStarted.toList)(env)
        (one(started), (previous, Vector(started), None))
      }
      else {
        if (fragment.execution.mustJoin) {
          val started = previous.map(_.startExecution(env))
          val step =
            fragment.
              updateExecution(_.setErrorAsFatal).
              startExecutionAfter((previousStarted ++ started).toList)(env)

          (emit((started :+ step).toList), (Vector.empty, Vector.empty, Some(step)))
        }
        else if ((previous :+ fragment).count(_.isExecutable) >= arguments.batchSize) {
          val started = (previous :+ fragment).map(_.startExecutionAfter(previousStep)(env))
          (emit(started.toList), (Vector.empty, previousStarted ++ started, previousStep))
        }
        else
          (done[ActionStack, Fragment], (previous :+ fragment, previousStarted, previousStep))
      }
    }

  }

  /** execute one fragment */
  def executeFragment(env: Env, timeout: Option[FiniteDuration] = None)(fragment: Fragment): Fragment =
    fragment.updateExecution(executeExecution(env, timeout))

  /** execute one Execution */
  def executeExecution(env: Env, timeout: Option[FiniteDuration] = None)(execution: Execution): Execution =
    timeout.fold(execution)(t => execution.setTimeout(t)).startExecution(env)

  def executeOnline(env: Env)(fragment: Fragment): AsyncStream[Fragment] =
    fragment.execution.continuation match {
      case Some(continue) =>
        Producer.evalProducer(fragment.executionResult.map { result =>
          continue(result).fold(emitAsyncDelayed(fragment))(
            fs => emitAsyncDelayed(fragment) append execute1(env)(fs.contents))
        })

      case None => emitAsyncDelayed(fragment)
    }
}

/**
 * helper functions for executing fragments
 */
object DefaultExecutor extends DefaultExecutor {

  def executeSpecWithoutShutdown(spec: SpecStructure, env: Env): SpecStructure =
    spec.|>((contents: AsyncStream[Fragment]) => contents |> sequencedExecution(env))

  def executeSpec(spec: SpecStructure, env: Env): SpecStructure = {
    spec.|>((contents: AsyncStream[Fragment]) => (contents |> sequencedExecution(env)).thenFinally(protect(env.shutdown)))
  }

  def runSpec(spec: SpecStructure, env: Env): List[Fragment] =
    runAction(executeSpec(spec, env).contents.runList).right.toOption.getOrElse(Nil)

  def runSpecification(spec: SpecificationStructure): List[Fragment] = {
    lazy val structure = spec.structure(Env())
    val env = Env(arguments = structure.arguments)
    runSpec(structure, env)
  }

  def runSpecification(spec: SpecificationStructure, env: Env): List[Fragment] = {
    lazy val structure = spec.structure(env)
    runAction(executeSpecWithoutShutdown(structure, env.copy(arguments = env.arguments <| structure.arguments)).contents.runList).
      right.toOption.getOrElse(Nil)
  }

  def runSpecificationFuture(spec: SpecificationStructure, env: Env): Future[List[Fragment]] = {
    lazy val structure = spec.structure(env)
    runActionFuture(executeSpecWithoutShutdown(structure, env.copy(arguments = env.arguments <| structure.arguments)).contents.runList)
  }

  def runSpecificationAction(spec: SpecificationStructure, env: Env): Action[List[Fragment]] = {
    lazy val structure = spec.structure(env)
    executeSpecWithoutShutdown(structure, env.copy(arguments = env.arguments <| structure.arguments)).contents.runList.
      flatMap { fs => fs.traverse(_.executionResult).as(fs) }
  }

  /** only to be used in tests */
  def executeFragments(fs: Fragments)(implicit env: Env = Env()) = executeAll(fs.fragments:_*)
  def executeAll(seq: Fragment*)(implicit env: Env = Env()) = executeSeq(seq)(env)
  def execute(f: Fragment)(implicit env: Env = Env()) = executeAll(f)(env).headOption.getOrElse(f)

  /** only to be used in tests */
  def executeSeq(seq: Seq[Fragment])(implicit env: Env = Env()): List[Fragment] =
    runAction((emitAsync(seq:_*) |> sequencedExecution(env)).runList).right.toOption.getOrElse(Nil)

  /** synchronous execution */
  def executeFragments1: AsyncTransducer[Fragment, Fragment] =
    executeFragments1(Env())

  /** synchronous execution with a specific environment */
  def executeFragments1(env: Env): AsyncTransducer[Fragment, Fragment] =
    transducers.transducer[ActionStack, Fragment, Fragment](executeFragment(env))
}
