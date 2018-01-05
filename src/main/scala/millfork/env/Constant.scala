package millfork.env

import millfork.error.ErrorReporting
import millfork.node.Position

object Constant {
  val Zero: Constant = NumericConstant(0, 1)
  val One: Constant = NumericConstant(1, 1)

  def error(msg: String, position: Option[Position] = None): Constant = {
    ErrorReporting.error(msg, position)
    Zero
  }

  def minimumSize(value: Long): Int = if (value < -128 || value > 255) 2 else 1 // TODO !!!
}

import millfork.env.Constant.minimumSize
import millfork.error.ErrorReporting
import millfork.node.Position

sealed trait Constant {

  def asl(i: Constant): Constant = i match {
    case NumericConstant(sa, _) => asl(sa.toInt)
    case _ => CompoundConstant(MathOperator.Shl, this, i)
  }

  def asl(i: Int): Constant = CompoundConstant(MathOperator.Shl, this, NumericConstant(i, 1))

  def requiredSize: Int

  def +(that: Constant): Constant = CompoundConstant(MathOperator.Plus, this, that)

  def -(that: Constant): Constant = CompoundConstant(MathOperator.Minus, this, that)

  def +(that: Long): Constant = if (that == 0) this else this + NumericConstant(that, minimumSize(that))

  def -(that: Long): Constant = this + (-that)

  def loByte: Constant = {
    if (requiredSize == 1) return this
    HalfWordConstant(this, hi = false)
  }

  def hiByte: Constant = {
    if (requiredSize == 1) Constant.Zero
    else HalfWordConstant(this, hi = true)
  }

  def subbyte(index: Int): Constant = {
    if (requiredSize <= index) Constant.Zero
    else index match {
      case 0 => loByte
      case 1 => hiByte
      case _ => SubbyteConstant(this, index)
    }
  }

  def isLowestByteAlwaysEqual(i: Int) : Boolean = false

  def quickSimplify: Constant = this

  def isRelatedTo(v: Variable): Boolean
}

case class UnexpandedConstant(name: String, requiredSize: Int) extends Constant {
  override def isRelatedTo(v: Variable): Boolean = false
}

case class NumericConstant(value: Long, requiredSize: Int) extends Constant {
  if (requiredSize == 1) {
    if (value < -128 || value > 255) {
      throw new IllegalArgumentException("The constant is too big")
    }
  }

  override def isLowestByteAlwaysEqual(i: Int) : Boolean = (value & 0xff) == (i&0xff)

  override def asl(i: Int): Constant = {
    val newSize = requiredSize + i / 8
    val mask = (1 << (8 * newSize)) - 1
    NumericConstant((value << i) & mask, newSize)
  }

  override def +(that: Constant): Constant = that + value

  override def +(that: Long) = NumericConstant(value + that, minimumSize(value + that))

  override def toString: String = if (value > 9) value.formatted("$%X") else value.toString

  override def isRelatedTo(v: Variable): Boolean = false
}

case class MemoryAddressConstant(var thing: ThingInMemory) extends Constant {
  override def requiredSize = 2

  override def toString: String = thing.name

  override def isRelatedTo(v: Variable): Boolean = thing.name == v.name
}

case class HalfWordConstant(base: Constant, hi: Boolean) extends Constant {
  override def quickSimplify: Constant = {
    val simplified = base.quickSimplify
    simplified match {
      case NumericConstant(x, size) => if (hi) {
        if (size == 1) Constant.Zero else NumericConstant((x >> 8) & 0xff, 1)
      } else {
        NumericConstant(x & 0xff, 1)
      }
      case _ => HalfWordConstant(simplified, hi)
    }
  }

  override def requiredSize = 1

  override def toString: String = base + (if (hi) ".hi" else ".lo")

  override def isRelatedTo(v: Variable): Boolean = base.isRelatedTo(v)
}

case class SubbyteConstant(base: Constant, index: Int) extends Constant {
  override def quickSimplify: Constant = {
    val simplified = base.quickSimplify
    simplified match {
      case NumericConstant(x, size) => if (index >= size) {
        Constant.Zero
      } else {
        NumericConstant((x >> (index * 8)) & 0xff, 1)
      }
      case _ => SubbyteConstant(simplified, index)
    }
  }

  override def requiredSize = 1

  override def toString: String = base + (index match {
    case 0 => ".lo"
    case 1 => ".hi"
    case 2 => ".b2"
    case 3 => ".b3"
  })

  override def isRelatedTo(v: Variable): Boolean = base.isRelatedTo(v)
}

object MathOperator extends Enumeration {
  val Plus, Minus, Times, Shl, Shr,
  DecimalPlus, DecimalMinus, DecimalTimes, DecimalShl, DecimalShr,
  And, Or, Exor = Value
}

case class CompoundConstant(operator: MathOperator.Value, lhs: Constant, rhs: Constant) extends Constant {
  override def quickSimplify: Constant = {
    val l = lhs.quickSimplify
    val r = rhs.quickSimplify
    (l, r) match {
      case (NumericConstant(lv, ls), NumericConstant(rv, rs)) =>
        var size = ls max rs
        val value = operator match {
          case MathOperator.Plus => lv + rv
          case MathOperator.Minus => lv - rv
          case MathOperator.Times => lv * rv
          case MathOperator.Shl => lv << rv
          case MathOperator.Shr => lv >> rv
          case MathOperator.Exor => lv ^ rv
          case MathOperator.Or => lv | rv
          case MathOperator.And => lv & rv
          case _ => return this
        }
        operator match {
          case MathOperator.Times | MathOperator.Shl =>
            val mask = (1 << (size * 8)) - 1
            if (value != (value & mask)){
              size = ls + rs
            }
          case _ =>
        }
        NumericConstant(value, size)
      case _ => CompoundConstant(operator, l, r)
    }
  }


  import MathOperator._

  override def +(that: Constant): Constant = {
    that match {
      case NumericConstant(n, _) => this + n
      case _ => super.+(that)
    }
  }

  override def +(that: Long): Constant = {
    if (that == 0) {
      return this
    }
    val That = that
    val MinusThat = -that
    this match {
      case CompoundConstant(Plus, NumericConstant(MinusThat, _), r) => r
      case CompoundConstant(Plus, l, NumericConstant(MinusThat, _)) => l
      case CompoundConstant(Plus, NumericConstant(x, _), r) => CompoundConstant(Plus, r, NumericConstant(x + that, minimumSize(x + that)))
      case CompoundConstant(Plus, l, NumericConstant(x, _)) => CompoundConstant(Plus, l, NumericConstant(x + that, minimumSize(x + that)))
      case CompoundConstant(Minus, l, NumericConstant(That, _)) => l
      case _ => CompoundConstant(Plus, this, NumericConstant(that, minimumSize(that)))
    }
  }

  private def plhs = lhs match {
    case _: NumericConstant | _: MemoryAddressConstant => lhs
    case _ => "(" + lhs + ')'
  }

  private def prhs = lhs match {
    case _: NumericConstant | _: MemoryAddressConstant => rhs
    case _ => "(" + rhs + ')'
  }

  override def toString: String = {
    operator match {
      case Plus => f"$plhs + $prhs"
      case Minus => f"$plhs - $prhs"
      case Times => f"$plhs * $prhs"
      case Shl => f"$plhs << $prhs"
      case Shr => f"$plhs >> $prhs"
      case DecimalPlus => f"$plhs +' $prhs"
      case DecimalMinus => f"$plhs -' $prhs"
      case DecimalTimes => f"$plhs *' $prhs"
      case DecimalShl => f"$plhs <<' $prhs"
      case DecimalShr => f"$plhs >>' $prhs"
      case And => f"$plhs & $prhs"
      case Or => f"$plhs | $prhs"
      case Exor => f"$plhs ^ $prhs"
    }
  }

  override def requiredSize: Int = lhs.requiredSize max rhs.requiredSize

  override def isRelatedTo(v: Variable): Boolean = lhs.isRelatedTo(v) || rhs.isRelatedTo(v)
}