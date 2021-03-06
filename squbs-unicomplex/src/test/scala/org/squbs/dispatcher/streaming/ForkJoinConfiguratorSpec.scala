/*
 * Copyright 2015 PayPal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.squbs.dispatcher.streaming

import java.util.concurrent.TimeUnit
import javax.management.ObjectName

import akka.actor.ActorSystem
import akka.io.IO
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.{BeforeAndAfterAll, Inspectors, Matchers, WordSpecLike}
import org.squbs.lifecycle.GracefulStop
import org.squbs.unicomplex.{Timeouts, JMX, Unicomplex, UnicomplexBoot}
import Timeouts._
import org.squbs.unicomplex.streaming._

import scala.concurrent.Await

object ForkJoinConfiguratorSpec {

  val dummyJarsDir = getClass.getClassLoader.getResource("classpaths/streaming").getPath

  val classPaths = Array(
    "DummyCube",
    "DummyCubeSvc",
    "DummySvc",
    "DummySvcActor",
    "StashCube",
    "DummyExtensions.jar"
  ) map (dummyJarsDir + "/" + _)

  val (_, _, port) = temporaryServerHostnameAndPort()

  val jmxPrefix = "forkJoinConfiguratorSpec"

  val config = ConfigFactory.parseString(
    s"""
      |squbs {
      |  actorsystem-name = streaming-forkJoinConfiguratorSpec
      |  ${JMX.prefixConfig} = true
      |  experimental-mode-on = true
      |}
      |
      |default-listener.bind-port = $port
      |
      |akka.actor {
      |  default-dispatcher {
      |    default-executor.fallback = org.squbs.dispatcher.ForkJoinConfigurator
      |    fork-join-executor {
      |      # Avoid JMX naming conflict in case of multiple tests.
      |      jmx-name-prefix = $jmxPrefix
      |    }
      |  }
      |}
    """.stripMargin)

  val boot = UnicomplexBoot(config)
    .createUsing {(name, config) => ActorSystem(name, config)}
    .scanComponents(classPaths)
    .initExtensions.start()
}

class ForkJoinConfiguratorSpec extends TestKit(ForkJoinConfiguratorSpec.boot.actorSystem) with ImplicitSender
    with WordSpecLike with Matchers with Inspectors with BeforeAndAfterAll with AsyncAssertions {

  import ForkJoinConfiguratorSpec._
  import system.dispatcher
  implicit val am = ActorMaterializer()

  "The ForkJoinConfigurator" must {

    "have some actors started" in {
      val w = new Waiter

      system.actorSelection("/user/DummyCube").resolveOne().onComplete { result =>
        w {
          assert(result.isSuccess)
        }
        w.dismiss()
      }
      w.await()
    }

    "be able to handle a simple Akka Http request" in {
      Await.result(entityAsString(s"http://127.0.0.1:$port/dummysvc/msg/hello"), awaitMax) should be("^hello$")
    }

    "expose proper ForkJoinPool MXBean stats" in {
      import org.squbs.unicomplex.JMX._
      val fjName =
        new ObjectName(jmxPrefix + '.' + forkJoinStatsName + "streaming-forkJoinConfiguratorSpec-akka.actor.default-dispatcher")

      get(fjName, "PoolSize").asInstanceOf[Int] should be > 0
      get(fjName, "ActiveThreadCount").asInstanceOf[Int] should be >= 0
      get(fjName, "Parallelism").asInstanceOf[Int] should be > 0
      get(fjName, "StealCount").asInstanceOf[Long] should be > 0L
      get(fjName, "Mode").asInstanceOf[String] should be ("Async")
      get(fjName, "QueuedSubmissionCount").asInstanceOf[Int] should be >= 0
      get(fjName, "QueuedTaskCount").asInstanceOf[Long] should be >= 0L
      get(fjName, "RunningThreadCount").asInstanceOf[Int] should be >= 0
      get(fjName, "Quiescent") shouldBe a[java.lang.Boolean]
    }
  }

  override def afterAll() {
    Unicomplex(system).uniActor ! GracefulStop
  }
}
