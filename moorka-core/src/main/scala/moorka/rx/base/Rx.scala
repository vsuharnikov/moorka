package moorka.rx.base

import moorka.rx.death.Mortal

import scala.concurrent.{ExecutionContext, Future}
import scala.ref.WeakReference
import scala.util.Try

/**
 * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
 */
sealed trait Rx[+A] extends Mortal {

  @inline def >>=[B](f: A ⇒ Rx[B]): Rx[B] = flatMap(f)

  def flatMap[B](f: A ⇒ Rx[B]): Rx[B]

  def foreach[U](f: A ⇒ U): Rx[Unit] = {
    flatMap { x ⇒
      f(x)
      Dummy
    }
  }

  def once[U](f: A ⇒ U): Rx[Unit] = {
    flatMap { x ⇒
      f(x)
      Killer
    }
  }

  def until(f: A ⇒ Boolean): Rx[Unit] = {
    flatMap { x ⇒
      if (!f(x)) Killer
      else Dummy
    }
  }

  def map[B](f: A ⇒ B): Rx[B] = {
    flatMap(x ⇒ Val(f(x)))
  }

  def zip[B](wth: Rx[B]): Rx[(A, B)] = {
    flatMap { a ⇒
      wth flatMap { b ⇒
        Val((a, b))
      }
    }
  }

  def filter(f: A ⇒ Boolean): Rx[A] = flatMap { x ⇒
    if (f(x)) Val(x)
    else Dummy
  }

  @inline def filterWith(f: A ⇒ Boolean): Rx[A] = filter(f)

  def drop(num: Int): Rx[A] = {
    var drops = 0
    flatMap { x ⇒
      if (drops < num) {
        drops += 1
        Dummy
      }
      else {
        Val(x)
      }
    }
  }

  def take(num: Int): Rx[Seq[A]] = {
    val channel = Channel[Seq[A]]()
    val seq = collection.mutable.Buffer[A]()
    foreach { value ⇒
      seq += value
      if (seq.length == num) {
        channel.update(Seq(seq:_*))
        seq.remove(0, seq.length)
      }
    }
    channel
  }

  // TODO this code doesn't work
  def fold[B](z: B)(op: (B, A) => B): Rx[B] = {
    val rx = Var(z)
    rx pull {
      flatMap { a =>
        rx map { b =>
          op(b, a)
        }
      }
    }
    rx
  }

  def or[B](b: Rx[B]): Rx[Either[A, B]] = {
    val rx = Channel[Either[A, B]]()
    val left: Rx[Either[A, B]] = map(x ⇒ Left(x))
    val right: Rx[Either[A, B]] = b.map(x ⇒ Right(x))
    rx.pull(left)
    rx.pull(right)
    rx
  }

  @deprecated("Use foreach() instead subscribe()", "0.4.0")
  def subscribe[U](f: A ⇒ U): Rx[Unit] = foreach(f)

  @deprecated("Use foreach() instead observe()", "0.4.0")
  def observe[U](f: ⇒ U): Rx[Unit] = foreach(_ ⇒ f)
}

sealed trait Source[A] extends Rx[A] {
  
  /**
   * List of bindings generated by this source. When updated fired
   * all bindings will get a new value 
   * @see [[flatMap]]
   */
  private[rx] var bindings: List[WeakReference[Binding[A, _]]] = Nil

  /**
   * List of values this source depends on.
   */
  private[rx] var upstreams: List[Rx[_]] = Nil
  
  /**
   * Broadcast `v` to bindings. Removes bindings dropped by GC. 
   * @param v new value
   */
  private[rx] def update(v: A): Unit = {
    bindings foreach { x ⇒
      x.get match {
        case Some(f) ⇒ f.run(v)
        case None ⇒
      }
    }
    bindings = bindings filter { x ⇒
      x.get match {
        case Some(_) ⇒ true
        case None ⇒ false
      }
    }
  }

  private[rx] def attachBinding(ref: WeakReference[Binding[A, _]]) = {
    bindings ::= ref
  }

  private[rx] def detachBinding(ref: WeakReference[Binding[A, _]]) = {
    bindings = bindings.filter(_ != ref)
  }

  @deprecated("Use pull() instead emit()", "0.4.0")
  def emit(v: A): Unit = {
    update(v)
  }

  @inline def <<=(rx: Rx[A]) = pull(rx)

  def pull(rx: Rx[A]) = rx match {
    case Val(x) ⇒
      update(x)
    case Killer ⇒
      kill()
    case Dummy ⇒
    // Do nothing
    case upstream ⇒
      upstreams ::= upstream foreach update
  }

  def flatMap[B](f: A ⇒ Rx[B]): Rx[B]

  def kill() = {
    bindings = Nil
    upstreams.foreach(_.kill())
    upstreams = Nil
  }
}

object Channel {
  def signal() = new Channel[Unit]() {
    def fire() = update(())
  }

  def apply[T]() = new Channel[T]()
}

sealed class Channel[A]() extends Source[A] {

  def flatMap[B](f: (A) => Rx[B]): Rx[B] = new Binding(this, f)
}

final class RxFuture[A](future: Future[A])
                       (implicit executionContext: ExecutionContext)
  extends Rx[Try[A]] {

  val st = Var[Option[Try[A]]](None)

  future onComplete { x ⇒
    st.update(Some(x))
  }


  def flatMap[B](f: (Try[A]) => Rx[B]): Rx[B] = {
    st flatMap {
      case Some(x) ⇒ f(x)
      case None ⇒ Dummy
    }
  }

  override def kill(): Unit = {
    st.kill()
  }
}

final case class Var[A](private[rx] var x: A) extends Source[A] {

  override def update(v: A) = {
    x = v
    super.update(v)
  }

  @deprecated("Use foreach() instead subscribe(). Note that foreach calls `f` immediately", "0.4.0")
  override def subscribe[U](f: (A) => U): Rx[Unit] = drop(1).foreach(f)

  override def flatMap[B](f: (A) => Rx[B]): Rx[B] = {
    new StatefulBinding(Some(x), this, f)
  }

  override def once[U](f: (A) => U): Rx[Unit] = {
    // We don't need to create binding to
    // get value just once.
    f(x)
    Dummy
  }
}

final case class Val[+A](x: A) extends Rx[A] {

  def flatMap[B](f: (A) => Rx[B]): Rx[B] = f(x)

  def kill(): Unit = ()
}

case object Dummy extends Rx[Nothing] {
  
  def flatMap[B](f: Nothing ⇒ Rx[B]): Rx[B] = Dummy

  def kill() = ()
}

private[rx] case object Killer extends Rx[Nothing] {
  
  def flatMap[B](f: Nothing ⇒ Rx[B]): Rx[B] = Dummy

  def kill() = ()
}

private[rx] class Binding[From, To](parent: Source[From],
                                    lambda: From ⇒ Rx[To]) extends Source[To] {

  def run(x: From) = {
    // Cleanup upstreams
    upstreams.foreach(_.kill())
    upstreams = Nil
    // Pull value from upstream
    val upstream = lambda(x)
    pull(upstream)
  }

  val ref = WeakReference(this)
  parent.attachBinding(ref)

  override def kill(): Unit = {
    parent.detachBinding(ref)
    super.kill()
  }

  def flatMap[B](f: (To) => Rx[B]): Rx[B] = new Binding(this, f)
}

private[rx] class StatefulBinding[From, To](initialValue: Option[From],
                                            parent: Source[From],
                                            lambda: From ⇒ Rx[To])
  extends Binding[From, To](parent, lambda) {

  var state: Option[To] = None

  override private[rx] def update(v: To): Unit = {
    state = Some(v)
    super.update(v)
  }

  override def flatMap[B](f: (To) => Rx[B]): Rx[B] = {
    new StatefulBinding(state, this, f)
  }

  override def subscribe[U](f: (To) => U): Rx[Unit] = drop(1).foreach(f)

  initialValue foreach run
}
