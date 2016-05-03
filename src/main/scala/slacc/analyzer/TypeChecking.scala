package slacc.analyzer

import slacc.analyzer.Symbols.MethodSymbol
import slacc.analyzer.Types.{_}
import slacc.ast.Trees.{ExprTree, Program, _}
import slacc.utils.{Context, Pipeline}

object TypeChecking extends Pipeline[Program, Program] {

  /** Typechecking does not produce a value, but has the side effect of
   * attaching types to trees and potentially outputting error messages. */
  def run(ctx: Context)(prog: Program): Program = {
    import ctx.reporter._

    def tcExpr(expr: ExprTree, expected: Type*): Type = {
      val tpe: Type = expr match {
        case e: And => tcAnd(e)
        case e: Or => tcOr(e)
        case e: Plus => tcPlus(e)
        case e: Minus => tcMinus(e)
        case e: Times => tcTimes(e)
        case e: Div => tcDiv(e)
        case e: LessThan => tcLessThan(e)
        case e: Equals => tcEquals(e)
        case e: ArrayRead => tcArrayRead(e)
        case e: ArrayLength => tcArrayLength(e)
        case e: MethodCall => tcMethodCall(e)
        case e: IntLit => tcIntLit(e)
        case e: StringLit => tcStringLit(e)
        case e: True => tcTrue(e)
        case e: False => tcFalse(e)
        case e: Identifier => tcIdentifier(e)
        case e: Self => tcSelf(e)
        case e: NewIntArray => tcNewIntArray(e)
        case e: New => tcNew(e)
        case e: Not => tcNot(e)
        case e: Block => tcBlock(e)
        case e: If => tcIf(e)
        case e: While => tcWhile(e)
        case e: Println => tcPrintln(e)
        case e: Assign => tcAssign(e)
        case e: ArrayAssign => tcArrayAssign(e)
        case e: Strof => tcStrof(e)
        case _ => fatal ("tcExpr: what happened")
      }

      def tcAnd(e: And): Type = {
        tcExpr(e.lhs, TBoolean)
        tcExpr(e.rhs, TBoolean)
        TBoolean
      }

      def tcOr(e: Or): Type = {
        tcExpr(e.lhs, TBoolean)
        tcExpr(e.rhs, TBoolean)
        TBoolean
      }

      def tcPlus(e: Plus): Type = {
        val possibleTypes = TInt :: TString :: List()
        val l = tcExpr(e.lhs, possibleTypes:_*)
        val r = tcExpr(e.rhs, possibleTypes:_*)
        l match {
          case TInt => r match {
            case TInt => TInt
            case TString => TString
            case _ => fatal("tcPlus: werent strings or ints")
          }
          case TString => r match {
            case TInt => TString
            case TString => TString
            case _ => fatal("tcPlus: werent strings or ints")
          }
          case _ => fatal("tcPlus: werent strings or ints")
        }
      }

      def tcMinus(e: Minus): Type = {
        val l = tcExpr(e.lhs, TInt)
        val r = tcExpr(e.rhs, TInt)
        TInt
      }

      def tcTimes(e: Times): Type = {
        val l = tcExpr(e.lhs, TInt)
        val r = tcExpr(e.rhs, TInt)
        TInt
      }

      def tcDiv(e: Div): Type = {
        val l = tcExpr(e.lhs, TInt)
        val r = tcExpr(e.rhs, TInt)
        TInt
      }

      def tcLessThan(e: LessThan): Type = {
        val l = tcExpr(e.lhs, TInt)
        val r = tcExpr(e.rhs, TInt)
        TInt
      }

      def tcEquals(e: Equals): Type = {
        val primitives = TInt :: TString :: TBoolean :: TIntArray :: List()
        val accepted: List[Type] = anyObject :: primitives
        val l = tcExpr(e.lhs, accepted:_*)
        l match {
          case TObject(klass) => tcExpr(e.rhs, anyObject); TBoolean
          case p if primitives.contains(l) => tcExpr(e.rhs, p); TBoolean
          case _ => {
            val r : Type = tcExpr(e.rhs)
            fatal("Type error: Equality operator requires compatible types, but found types: "
              + l + " and " + r)
            TError
          }
        }
      }

      def tcArrayRead(e: ArrayRead): Type = {
        tcExpr(e.arr, TIntArray)
        tcExpr(e.index, TInt)
      }

      def tcArrayLength(e: ArrayLength): Type = {
        tcExpr(e.arr, TIntArray)
        TInt
      }

      def tcMethodCall(e: MethodCall): Type = {
        tcExpr(e.obj, anyObject)
        val params = e.meth.getSymbol
        var argListTypes = List[Type]()
        params match {
          case p: MethodSymbol => {
            p.argList.foreach(a => argListTypes:+a.getType)
          }
          case _ => TError // should never happen
        }
        if (e.args.length != argListTypes.length) TError // is this okay
        for ((arg, t) <- (e.args zip argListTypes)) yield tcExpr(arg, t)
        tcExpr(e.meth, e.meth.getType)
      }

      def tcIntLit(e: IntLit): Type = TInt

      def tcStringLit(e: StringLit): Type = TString

      def tcTrue(e: True): Type = TBoolean

      def tcFalse(e: False): Type = TBoolean

      def tcIdentifier(e: Identifier): Type = {
        e.getType
      }

      def tcSelf(e: Self): Type = {
        e.getSymbol.getType
      }

      def tcNewIntArray(e: NewIntArray): Type = {
        tcExpr(e.size, TInt)
      }

      def tcNew(e: New): Type = {
        tcExpr(e.tpe)
      }

      def tcNot(e: Not): Type = {
        tcExpr(e.expr, TBoolean)
      }

      def tcBlock(e: Block): Type = {
        e.exprs.foreach(ex => tcExpr(ex))
        TUnit // is this the type of a block
      }

      def tcIf(e: If): Type = {
        tcExpr(e.expr, TBoolean)
        val thnBranch = tcExpr(e.thn)
        e.els match {
          case Some(els) => {
            val elsBranch = tcExpr(els)
            if (thnBranch == elsBranch) thnBranch
            else TError
          } case None => thnBranch
        }
      }

      def tcWhile(e: While): Type = {
        tcExpr(e.cond, TBoolean)
        tcExpr(e.body, TUnit)
        TUnit
      }

      def tcPrintln(e: Println): Type = {
        tcExpr(e.expr, TString)
        TUnit
      }

      def tcAssign(e: Assign): Type = {
        val lhs = tcExpr(e.id)
        val rhs = tcExpr(e.expr)
        if (lhs == rhs) TUnit // ya
        else if (rhs.isSubTypeOf(lhs)) TUnit // this is ok
        else TError
      }

      def tcArrayAssign(e: ArrayAssign): Type = {
         tcExpr(e.id, TIntArray)
         tcExpr(e.index, TInt)
         tcExpr(e.expr, TInt)
         TUnit
      }

      def tcStrof(e: Strof): Type = {
         tcExpr(e.expr, TInt, TBoolean)
         TString
      }

      // Check result and return a valid type in case of error
      if (expected.isEmpty) {
        tpe
      } else if (!expected.exists(e => tpe.isSubTypeOf(e))) {
        error("Type error: expected: " + expected.toList.mkString(" or ") + ", found: " + tpe, expr)
        expected.head
      } else {
        tpe
      }
    }

    prog
  }

}