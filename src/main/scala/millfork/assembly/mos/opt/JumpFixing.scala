package millfork.assembly.mos.opt

import java.util.concurrent.atomic.AtomicInteger

import millfork.assembly.mos.{AddrMode, AssemblyLine, Opcode}
import millfork.assembly.mos.Opcode._
import millfork.env.{Label, MemoryAddressConstant, NormalFunction}
import millfork.error.ErrorReporting
import millfork.CompilationOptions

/**
  * @author Karol Stasiak
  */
object JumpFixing {
  val counter = new AtomicInteger(80000)

  def generateNextLabel() = f".lj${counter.getAndIncrement()}%05d"

  def invalidShortJump(thisOffset: Int, labelOffset: Int): Boolean = {
    val distance = labelOffset - (thisOffset + 2)
    distance.toByte != distance
  }

  private def negate(opcode: Opcode.Value) = opcode match {
    case BEQ => BNE
    case BNE => BEQ
    case BCC => BCS
    case BCS => BCC
    case BVC => BVS
    case BVS => BVC
    case BMI => BPL
    case BPL => BMI
  }

  def apply(f: NormalFunction, code: List[AssemblyLine], options: CompilationOptions): List[AssemblyLine] = {
    val offsets = new Array[Int](code.length)
    var o = 0
    code.zipWithIndex.foreach{
      case (line, ix) =>
        offsets(ix) = o
        o += line.sizeInBytes
    }
    val labelOffsets = code.zipWithIndex.flatMap {
      case (AssemblyLine(LABEL, _, MemoryAddressConstant(Label(label)), _), ix) => Some(label -> offsets(ix))
      case _ => None
    }.toMap
    var changed = false
    val result = code.zipWithIndex.flatMap {
      case (line@AssemblyLine(op, AddrMode.Relative, MemoryAddressConstant(Label(label)), true), ix) =>
        labelOffsets.get(label).fold(List(line)) { labelOffset =>
          val thisOffset = offsets(ix)
          if (invalidShortJump(thisOffset, labelOffset)) {
            changed = true
            val long: List[AssemblyLine] = op match {
              case BRA => List(line.copy(opcode = JMP, addrMode = AddrMode.Absolute))
              case BSR => List(line.copy(opcode = JSR, addrMode = AddrMode.Absolute))
              case _ =>
                val label = generateNextLabel()
                List(
                  AssemblyLine.relative(negate(op), label),
                  line.copy(opcode = JMP, addrMode = AddrMode.Absolute),
                  AssemblyLine.label(label)
                )
            }
            ErrorReporting.debug("Changing branch from short to long")
            ErrorReporting.trace(line.toString)
            ErrorReporting.trace("     ↓")
            long.foreach(l => ErrorReporting.trace(l.toString))
            long
          } else List(line)
        }
      case (line, _) => List(line)
    }
    if (changed) apply(f, result, options) else result
  }


}