package com.gemini.jobcoin.mixer

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.UUID
import scala.collection.immutable.HashMap
import scala.concurrent.Future
import scala.math.BigDecimal
import scala.util.{Try, Success, Failure}
import akka.actor.Actor
import akka.actor.ActorRef
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
    private var _mixing = Set[String]()
    val stateLocation = config.getString("jobcoin.stateLocation")

    def allAddresses = _addresses
    def addresses(address: String) = _addresses(address)
    def addAddresses(origin: String, destinations: Seq[String]) = {
      _addresses = _addresses + (origin -> destinations)
      state.save()
    }

    def balances(address: String) = _balances.synchronized{
      _balances.getOrElse(address, "0")
    }
    def newBalance(account: String) = {
      _balances = _balances + (account -> "0")
    }
    def addBalance(account: String, amount: BigDecimal) = _balances.synchronized{
      alterBalance(account, amount, _ + _)
    }
    def subtractBalance(account: String, amount: BigDecimal) = _balances.synchronized{
      alterBalance(account, amount, _ - _)
    }
    def alterBalance(account: String, amount: BigDecimal, op: (BigDecimal, BigDecimal) => BigDecimal) = _balances.synchronized{
      val curr = BigDecimal(balances(account))
      val balance = {
        val rawBalance = op(curr, amount)
        val balance =
          if(0 < rawBalance) rawBalance
          else 0

        balance.toString
      }
      _balances = _balances + (account -> balance)
      state.save()
    }

    def mixing(state: Boolean, address: String) = {
      //println(s"mixing $address $state")
      if(state) _mixing = _mixing + address
      else _mixing = _mixing - address
    }
    def getMixing() = _mixing

    private object state{
      import java.io._
      def save() = Try{
        val writer = new ObjectOutputStream(new FileOutputStream(stateLocation))
        writer.writeObject(_addresses)
        writer.writeObject(_balances)
        writer.flush()
        writer.close()
      }

      def load() = Try{
        val reader = new ObjectInputStream(new FileInputStream(stateLocation))
        _addresses = reader.readObject().asInstanceOf[HashMap[String, Seq[String]]]
        _balances = reader.readObject().asInstanceOf[HashMap[String,String]]
        //println(_addresses)
        //println(_balances)
      }
    }
    state.load() match{
      case Success(_) => //pass
      case Failure(th) =>
        th.printStackTrace()
    }
  }
  def system = context.system
  def config = system.settings.config

  val houseAddress = config.getString("jobcoin.houseAddress")
  val maxFee = config.getDouble("jobcoin.maxFee")
  val stateLocation = config.getString("jobcoin.stateLocation")
  val factorTx: Double = {
    val r = Math.abs(config.getDouble("jobcoin.transferFactor"))
    if(r < 1) r + 1
    else r
  }
  val poll = config.getInt("jobcoin.pollInterval")
  val pollMix = (poll.toDouble / factorTx).toInt - 1
  val mixPoll = (poll.toDouble / factorTx).toInt
  val minTx =
    Math.abs(config.getDouble("jobcoin.minimumTransfer"))
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
    case Mix(addressO) => addressO match {
      case None =>
        var idx = 0
        data.getMixing().foreach{address =>
          idx = idx + 1
          (0 to factorTx.toInt).foreach{i =>
            delay(self, Mix(Some(address)), pollMix * (i + 1))
          }
        }
      case Some(address) =>
        mix(address)
    }
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
            delay(self, Mix(None))
            delay(self, Transfer(None, None))
          }
        case (Some(source), Some(amount)) =>
          val transfer = (network() ? MixerNetwork.Transfer(source, houseAddress, amount))
          transfer.onComplete{t =>
            t match{
              case Success(_) =>
                data.addBalance(source, amount)
              case Failure(_) =>
                //pass
            }
            data.mixing(true, source)
          }
        case _ => //pass
      }
  }

  def delay(target: ActorRef, message: Any, wait: Int = poll): Try[Unit] = {
    Try{system.scheduler.scheduleOnce(
      Duration.ofMillis(wait),
      target, message,
      system.dispatcher, null)}
  }

  def mix(address: String) = {
    //println("mix " + address)
    //choose random mix address
    val targets = scala.util.Random.shuffle(data.addresses(address))
    val target = targets(0)

    computeFee(data.balances(address)).foreach{case (rawBalance, fee) =>
      val balance = rawBalance - fee
      data.subtractBalance(address, fee)

      val amount: BigDecimal = {
        val amt = balance / factorTx / targets.size
        if(minTx < amt) amt
        else if(minTx < balance) BigDecimal(minTx)
        else balance
      }
      //println(s"vvvvv\naddress: $address\nbalance: $rawBalance\nafterFee: $balance\ntx: $amount\n^^^^^")
      val message = MixerNetwork.Transfer(houseAddress, target, amount)
      (network() ? message).onComplete{t =>
        t match{
          case Success(_) =>
            data.subtractBalance(address, amount)
          case Failure(th) =>
            // pass
        }
        if(BigDecimal(data.balances(address)) <= 0){
          data.mixing(false, address)
        }
      }
    }
  }

  def computeFee(rawBalance: String): Option[(BigDecimal, BigDecimal)] = {
    val balance = BigDecimal(rawBalance)
    if(balance <= 0) None
    else {
      val factor = ((0.001 + scala.util.Random.nextFloat * 0.005) / factorTx)
      val fee = balance * factor
      Some(
        (balance,
        if(maxFee < fee) maxFee
        else fee))
    }
  }
}

object MixerCreator{
  sealed trait CreatorMessage
  //requests
  final case class CreateMixer(addresses: Seq[String]) extends CreatorMessage
  final case class RetrieveAddresses(address: String) extends CreatorMessage
  final case class Mix(address: Option[String]) extends CreatorMessage
  final case class Transfer(address: Option[String] = None, amount: Option[BigDecimal] = None)
  final case object SaveState extends CreatorMessage

  //responses
  final case class NewMixerAddress(address: String) extends CreatorMessage
  final case class MixedAddresses(addresses: Seq[String]) extends CreatorMessage
}
