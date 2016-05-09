package slacc
package code

import ast.Trees._
import analyzer.Symbols._
import analyzer.Types._
import cafebabe._
import AbstractByteCodes.{New => _, _}
import ByteCodes._
import utils._

object CodeGeneration extends Pipeline[Program, Unit] {

  def run(ctx: Context)(prog: Program): Unit = {
    import ctx.reporter._

    def addFieldToClass(cls:ClassFile, name: String, tpe:Type): Unit = {
      cls.addField(getPrefixForType(tpe), name)
    }

    def addMethodToClass(cls: ClassFile, name: String, args : List[VariableSymbol], returnType: Type): CodeHandler = {
      var paramsString = new StringBuilder()
      val paramsList : List[Type] = List()
      args.foreach(z => paramsList:+(z.getType))
      paramsList.foreach(p => paramsString.append(getPrefixForType(p)))
      cls.addMethod(getPrefixForType(returnType), name, paramsString.toString).codeHandler
    }

    /** Writes the proper .class file in a given directory. An empty string for dir is equivalent to "./". */
    def generateClassFile(sourceName: String, ct: ClassDecl, dir: String): Unit = {
      // TODO: Create code handler, save to files ...
      val classFile = ct.parent match {
        case Some(p) => new ClassFile(ct.id.value, Some(p.value))
        case None => new ClassFile(ct.id.value, None)
      }
      // Add fields
      ct.vars.foreach(v => addFieldToClass(classFile, v.id.value, v.getSymbol.getType))
      // Add methods
      for (m <- ct.methods) {
        val ch = addMethodToClass(classFile, m.id.value, m.getSymbol.argList, m.retType.getType)
        generateMethodCode(ch, m)
      }
      classFile.setSourceFile(sourceName)
      val fileDest = dir match {
        case "" => "./"
        case _ => dir
      }
      classFile.writeToFile(fileDest + ct.id.value + ".class")
    }

    def getPrefixForType(typ: Type): String = {
      typ match {
        case TError => fatal("getPrefixForType: got " + TError)
        case TUntyped => fatal("getPrefixForType: got " + TUntyped)
        case TInt => "I"
        case TBoolean => "Z"
        case TString => "L"
        case TUnit => "V" //void
        case TIntArray => "[I"
        case anyObject => "?"
        case _ => fatal("getPrefixForType: got " + typ)
      }
    }

    // a mapping from variable symbols to positions in the local variables
    // of the stack frame
    def generateMethodCode(ch: CodeHandler, mt: MethodDecl): Unit = {
      val methSym = mt.getSymbol

      // TODO: Emit code
      def generateExprCode (ex: ExprTree): Unit = {
        ex match {
          case t : And => {
            val labelName = ch.getFreshLabel("shortCircuitAnd")
            generateExprCode(t.lhs) // push lhs on stack
            ch << Ldc(0) // push 0 on the stack
            ch << If_ICmpEq(labelName) // if LHS is False (== 0), jump to the labelName, don't eval rhs
            generateExprCode(t.rhs) // whatever is pushed on the stack here is the result of the eval
            ch << Label(labelName) // jumps here if !LHS
          } case t : Or => {
            val labelName = ch.getFreshLabel("shortCircuitOr")
            generateExprCode(t.lhs) // push lhs on stack
            ch << Ldc(1) // opposite of case above
            ch << If_ICmpEq(labelName)
            generateExprCode(t.rhs)
            ch << Label(labelName)
          } case t : Plus => {
            if (t.getType == TInt) {
              // Addition - trick is to load left and right hand sides first... i think
              generateExprCode(t.lhs)
              generateExprCode(t.rhs)
              ch << IADD
            } else if (t.getType == TString) {
              // String concat
              val buffer = new StringBuilder()
              generateExprCode(t.lhs)
              generateExprCode(t.rhs)
              //buffer.append(t.lhs.value) // if we get this far, have faith it's a string
              //buffer.append(t.rhs.value)
              //ch << Ldc(buffer.toString)
              // wait a minute
              ???
            }
          } case t : Minus => {
            generateExprCode(t.lhs)
            generateExprCode(t.rhs)
            ch << ISUB
          } case t : Times => {
            generateExprCode(t.lhs)
            generateExprCode(t.rhs)
            ch << IMUL
          } case t : Div => {
            generateExprCode(t.lhs)
            generateExprCode(t.rhs)
            ch << IDIV
          } case t : LessThan => {
            val labelName = ch.getFreshLabel("GEQ")
            generateExprCode(t.lhs)
            generateExprCode(t.rhs)
            ch << If_ICmpGe(labelName) // if LHS >= RHS jump to label
            ch << Ldc(1) // if here it's true LHS < RHS
            ch << Label(labelName)
            ch << Ldc(0)
          } case t : Equals => {
            val labelName = ch.getFreshLabel("notEqual")
            if (t.lhs.getType == TBoolean || t.lhs.getType == TInt) {
              generateExprCode(t.lhs) // counting on true to always push 1 to the stack :/
              generateExprCode(t.rhs)
              ch << If_ICmpNe(labelName)
              ch << Ldc(1)
              ch << Label(labelName)
              ch << Ldc(0)
            } else {

            }
          } case b : Block => {
            b.exprs.foreach(e => generateExprCode(e))
          } case ifthen : If => {
            ???
          } case w : While => {
            ???
          } case p : Println => {
            ch << GetStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
            generateExprCode(p.expr)
            ch << InvokeVirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
          } case s : Strof => {
            if (s.expr.getType != TString) {
              ???
            }
          }
          case a : Assign => {
            ???
          } 
          case i : Identifier => {
            ???
          }
          case m :MethodCall => {
            ???
          }
          case e: ArrayRead => {
            ???
          }
          case e: ArrayLength => {
            ???
          }
          case e: ArrayAssign => {
            ???
          }
          case n: New => {
            ???
          }
          case i : IntLit => {
            // push it onto the stack
            ch << Ldc(i.value)
          }
          case b1 : True => {
            ch << Ldc(1)
          }
          case b2 : False => {
            ch << Ldc(0)
          }
          case s : StringLit => {
            ch << Ldc(s.value)
          }
        }
      }

      ch.freeze
    }

    val outDir = ctx.outDir.map(_.getPath+"/").getOrElse("./")

    val f = new java.io.File(outDir)
    if (!f.exists()) {
      f.mkdir()
    }

    val sourceName = ctx.files.head.getName

    // output code
    prog.classes foreach {
      ct => generateClassFile(sourceName, ct, outDir)
    }

    // Now do the main method
    // ...
  }

}
