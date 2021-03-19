package com.gemini.jobcoin.mixer

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.UUID
import scala.collection.immutable.HashMap
import scala.concurrent.Future
import scala.math.BigDecimal
import scala.util.{Try, Success, Failure}
import akka.actor.Actor
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.json._

import com.gemini.jobcoin.util._

class MixerCreator extends Actor{
  import MixerCreator._
  import context.dispatcher

  object data{
    private var _addresses = HashMap[String, Seq[String]]()
    private var _balances = HashMap[String, String]()
    val stateLocation = config.getString("jobcoin.stateLocation")

    def allAddresses = _addresses
    def addresses(address: String) = _addresses(address)
    def addAddresses(origin: String, destinations: Seq[String]) = {
      _addresses = _addresses + (origin -> destinations)
      state.save()
    }

    def balances(address: String) = _balances(address)
    def newBalance(account: String) = {
      _balances = _balances + (account -> "0")
    }
    def addBalance(account: String, amount: BigDecimal) = synchronized{
      val curr = BigDecimal(balances(account))
      _balances = _balances + (account -> (curr + amount).toString)
    }
    def subtractBalance(account: String, amount: BigDecimal) = synchronized{
      val curr = BigDecimal(balances(account))
      _balances = _balances + (account -> (curr - amount).toString)
    }

    private object state{
      import java.io._
      def save() = Try{
        val writer = new ObjectOutputStream(new FileOutputStream(stateLocation))
        writer.writeObject(_addresses)
        writer.writeObject(_balances)
      }

      def load() = Try{
        val reader = new ObjectInputStream(new FileInputStream(stateLocation))
        _addresses = reader.readObject().asInstanceOf[HashMap[String, Seq[String]]]
        _balances = reader.readObject().asInstanceOf[HashMap[String,String]]
      }
    }
    state.load()
  }
  val houseAddress = "a79f1151-957f-42ec-a525-24768d3327f2"
  val minIncrement = 0.00005
  def system = context.system
  def config = system.settings.config

  val stateLocation = config.getString("jobcoin.stateLocation")
  val _defaultTimeout = config.getInt("jobcoin.defaultTimeout")
  implicit val defaultTimeout =
    Timeout(
      _defaultTimeout,
      java.util.concurrent.TimeUnit.MILLISECONDS)
  val rest = Rest(_defaultTimeout)

  val networks = {
    var n = 0
    Seq.fill(config.getInt("jobcoin.networkActors")){
      n+=1
      system.actorOf(Props[MixerNetwork], name = s"network${n}")
    }
  }
  object network {
    private var idx = 0
    def apply() = {
      idx+=1
      if(idx >= networks.size) idx = 0
      networks(idx)
    }
  }

  def receive = {
    case CreateMixer(addresses) =>
      val newAddress = UUID.randomUUID().toString()
      sender ! NewMixerAddress(newAddress)
      data.addAddresses(newAddress, addresses)
      data.newBalance(newAddress)
      self ! SaveState
    case RetrieveAddresses(address) =>
      sender ! data.addresses(address)
    case Mix(address) =>
      mix(address)
    case Transfer(address, amount) =>
      (address, amount) match{
        case (None, _) =>
          data.allAddresses.keys.map{source =>
            network() ? MixerNetwork.CheckBalance(source)
          }.map{_.flatMap{case balance =>
              self ? balance
            }
          }.fold(Future()){(a, b) =>
            a.flatMap(_ => b)
          }.transform{_ =>
            Try{system.scheduler.scheduleOnce(
              Duration.ofMillis(config.getInt("jobcoin.pollInterval")),
              self, Transfer(None, None),
              system.dispatcher, null)}
          }
        case (Some(source), Some(amount)) =>
          val transfer = (network() ? MixerNetwork.Transfer(source, houseAddress, amount))
          transfer.onComplete{
            case Success(_) =>
              data.addBalance(source, amount)
              self ! Mix(source)
            case Failure(_) =>
              //pass
          }
        case _ => //pass
      }
  }

  def mix(address: String) = {
    val addresses = scala.util.Random.shuffle(data.addresses(address))
    val rawBalance = data.balances(address)
    val balance = balanceMinusFee(rawBalance)
    val amount = balance / (addresses.size)

    @annotation.tailrec
    def recurse(addresses: Seq[String], balance: BigDecimal): Unit = {
      if(0 < balance){
        addresses match{
          case Seq() =>
            // pass
          case target +: tail =>
            val newBalance = balance - amount
            (network() ? MixerNetwork.Transfer(houseAddress, target, amount)).map{
              case Success(_) =>
                data.subtractBalance(address, amount)
              case Failure(th) =>
                // pass
            }
            recurse(tail, newBalance)
        }
      }
    }
    recurse(addresses, balance)
  }

  def balanceMinusFee(rawBalance: String): BigDecimal = {
    val balance = BigDecimal(rawBalance)
    val truncate = BigDecimal(balance.toInt)
    val fee =
      // basically 1% or a small fraction of a coin whichever is least
      ((balance - truncate) * 0.00005).min(balance * 0.01)

    return balance - fee
  }
}

object MixerCreator{
  sealed trait CreatorMessage
  //requests
  final case class CreateMixer(addresses: Seq[String]) extends CreatorMessage
  final case class RetrieveAddresses(address: String) extends CreatorMessage
  final case class Mix(address: String)
  final case class Transfer(address: Option[String] = None, amount: Option[BigDecimal] = None)
  final case object SaveState extends CreatorMessage

  //responses
  final case class NewMixerAddress(address: String) extends CreatorMessage
  final case class MixedAddresses(addresses: Seq[String]) extends CreatorMessage
}
