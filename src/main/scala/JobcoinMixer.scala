package com.gemini.jobcoin

import java.util.UUID//--

import scala.io.StdIn
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.actor.Props
import akka.pattern.ask
import akka.stream.ActorMaterializer
import scala.concurrent.ExecutionContext.Implicits._

import com.gemini.jobcoin.mixer._

object JobcoinMixer {
  object CompletedException extends Exception { }

  def main(args: Array[String]): Unit = {
    // Create an actor system
    implicit val actorSystem = ActorSystem()
    implicit val materializer = ActorMaterializer()

    // Load Config
    val config = ConfigFactory.load()
    implicit val defaultTimeout =
      Timeout(
        config.getInt("jobcoin.defaultTimeout"),
        java.util.concurrent.TimeUnit.MILLISECONDS)

    // Test HTTP client
    // val client = new JobcoinClient(config)
    // client.testGet().map(response => println(s"Response:\n$response"))

    try {
      val mixerCreator = actorSystem.actorOf(Props[MixerCreator], name = "helloactor")
      mixerCreator ! MixerCreator.Transfer()

      while (true) {
        println(prompt)
        val line = StdIn.readLine()

        line match{
          case "" => error_emptyInput()
          case "quit" | "exit" => throw CompletedException
          case _ =>
            val addresses = line.split(",")
            val result = mixerCreator ? MixerCreator.CreateMixer(addresses)
            result.foreach{
              case MixerCreator.NewMixerAddress(depositAddress) =>
                println(s"You may now send Jobcoins to address $depositAddress. They will be mixed and sent to your destination addresses.")
            }
        }
      }
    } catch {
      case CompletedException => println("Quitting...")
    } finally {
      actorSystem.terminate()
    }
  }

  def error_emptyInput(){
    println(s"You must specify empty addresses to mix into!\n$helpText")
  }

  val prompt: String = "Please enter a comma-separated list of new, unused Jobcoin addresses where your mixed Jobcoins will be sent."
  val helpText: String =
    """
      |Jobcoin Mixer
      |
      |Takes in at least one return address as parameters (where to send coins after mixing). Returns a deposit address to send coins to.
      |
      |Usage:
      |    run return_addresses...
    """.stripMargin
}
