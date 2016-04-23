package slacc
package analyzer

import utils._
import ast.Trees._
import Symbols._
import slacc.ast.Printer


object NameAnalysis extends Pipeline[Program, Program] {

  def run(ctx: Context)(prog: Program): Program = {
    import ctx.reporter._


    // Step 1: Collect symbols in declarations
    var globalScope = new GlobalScope()

    // ============ Collect =============
    def collectSymbols(node: Symbolic, scope: GlobalScope): Unit = {
      node match {
        case n: MainMethod => collectMainMethod(n, scope)
        case n: ClassDecl => collectClassDecl(n, scope)
        //      case n: VarDecl => collectVarDecl(n, scope)
        //      case n: MethodDecl => collectMethodDecl(n, scope)
        //      case n: Formal => collectFormal(n, scope)
        //      case n: Identifier => collectIdentifier(n, scope)
        case n: _ => sys.error("tried to collect something that needs to know its symbol scope")
      }
    }

    def collectMainMethod(n: MainMethod, scope: GlobalScope): Unit = {
      if (scope.mainClass != null) {
        fatal("collectMainMethod: Main class already declared", n)
      }
      scope.mainClass = new ClassSymbol(n.id.toString)
      collectMethodDecl(n.main, scope.mainClass)
    }

    def collectClassDecl(klass: ClassDecl, scope: GlobalScope): Unit = {
      val className = klass.id.toString
      val symbol = new ClassSymbol(className)
      klass.setSymbol(symbol)
      def addClass(): Unit = {
        scope.classes + (className -> symbol)
        klass.vars.foreach(v => collectVarDecl(v, symbol))
        klass.methods.foreach(m => collectMethodDecl(m , symbol))
      }
      scope.lookupClass(className) match {
        case Some(v) =>
          fatal("collectClassDecl: " + className + " already declared", klass)
        case None =>
          klass.parent match {
            case Some(v) => {
              if (scope.lookupClass(v.value).isEmpty) {
                fatal("collectClassDecl:" + className + " parent is not defined", klass)
              }
              symbol.parent = scope.lookupClass(v.value)
              if (hasInheritanceCycle(symbol, scope)) {
                fatal("collectClassDecl: " + className + " has an inheritanceCyckle", klass)
              }
              addClass()
            }
            case None => addClass()
          }
      }
    }

    def hasInheritanceCycle(symbol: ClassSymbol, scope: GlobalScope): Boolean = {
      var visited: Set[ClassSymbol] = Set()
      visited = visited + symbol
      var currentClass = symbol.parent
      while (currentClass.isDefined) {
        currentClass match {
          case Some(k) => {
            if (visited.contains(k)) {
              return false
            }
            currentClass = k.parent
          }
          case None => true
        }
      }
      false
    }

    def collectVarDecl(n: VarDecl, scope: Symbol): Unit = {
      val varName = n.id.toString
      val symbol = new VariableSymbol(varName)
      scope match {
        case s: ClassSymbol => {
          if (s.lookupVar(varName).isDefined) {
            fatal("collectVarDecl: class " + s.name + " already had a variable named " + varName, n)
          }
          s.members + (varName -> symbol)
        }
        case s: MethodSymbol => {
          // it's ok if has same name as a class variable
          // not ok if it's in declared vars
          if (s.lookupVar(varName).isDefined) {
            fatal("collectVarDecl: method " + s.name + " already had a variable named " + varName, n)
          }
          s.members + (varName -> symbol)

        }
        case s: _ => {
          sys.error("Collected a variable not in a Class or MethodSymbol!!")
        }
      }
    }

    def collectMethodDecl(method: MethodDecl, scope: ClassSymbol): Unit = {
      val methodName = method.id.toString
      val symbol = new MethodSymbol(methodName, scope)
      def addMethod(): Unit = {
        scope.methods + (methodName -> symbol)
        method.args.foreach(arg => collectFormal(arg, symbol))
        method.vars.foreach(v => collectVarDecl(v, symbol))
      }
      // TODO: How the fuck do we know if it's overloaded or not?!?
      scope.lookupMethod(methodName) match {
        case Some(m) => {
          fatal("collectMethodDecl: method " + methodName + " was already defined!", method)
        }
        case None => addMethod()
      }

    }

    def collectFormal(n: Formal, scope: MethodSymbol): Unit = {
      // it's ok if it has the same name as a class variable...
      val formalName = n.id.toString
      val symbol = new VariableSymbol(formalName)
      if (scope.argList.contains(symbol)) {
        fatal("collectFormal: argList already had a formal with this name!", n)
      }
      scope.argList = scope.argList :+ symbol
    }


    // ============== Attach ================

    def attachSymbols(node: Tree, scope: GlobalScope): Unit = {
      node match {
        case n: MainMethod => attachMainMethod(n, scope)
        case n: ClassDecl => attachClassDecl(n, scope)
        //      case n: VarDecl => collectVarDecl(n, scope)
        //      case n: MethodDecl => collectMethodDecl(n, scope)
        //      case n: Formal => collectFormal(n, scope)
        //      case n: Identifier => collectIdentifier(n, scope)
        case n: _ => sys.error("tried to attach something that needs a more specific scope")
      }
    }

    def attachMainMethod(main: MainMethod, scope: GlobalScope): Unit = {
      scope.lookupClass(main.id.value) match {
        case Some(s) => main.setSymbol(s)
        case None => sys.error("attachMainMethod: No symbol for main class")
      }
      attachMethod(main.main, main.getSymbol)
    }

    def attachClassDecl(classDecl: ClassDecl, scope: GlobalScope): Unit = {
      val className = classDecl.id.toString
      scope.lookupClass(className) match {
        case Some(klass) => classDecl.setSymbol(klass)
        case None => sys.error("attachClassDecl: No matching class for ID")
      }
      classDecl.methods.foreach(method => attachMethod(method, classDecl.getSymbol))
      classDecl.vars.foreach(v => attachVariable(v, classDecl.getSymbol))
    }

    def attachMethod(method: MethodDecl, scope: ClassSymbol): Unit = {
      val methodName = method.id.toString
      val symbol = scope.lookupMethod(methodName)
      symbol match {
        case Some(z) => method.setSymbol(z)
        case None => sys.error("attachVariable: No matching variable in class")
      }
      method.args.foreach(formal => attachFormal(formal, method.getSymbol))
      method.vars.foreach(v => attachVariable(v, method.getSymbol))
      attachRetType(method.retType, method.getSymbol)
    }

    def attachRetType(tpe: TypeTree, method: MethodSymbol): Unit = {
      attachTypeTree(tpe)
    }

    def attachTypeTree(tpe: TypeTree): Unit = {
      tpe match {
        case tpe: Identifier => {
          // look up in list of classes
          globalScope.lookupClass(tpe.value) match {
            case Some(z) => tpe.setSymbol(z)
            case None => sys.error("attachTypeTree: No matching class for identifier")
          }
        }
        case _ => // do nothing
      }
    }

    def attachFormal(formal: Formal, method: MethodSymbol): Unit = {
      // need to attach the id of the formal AND the type
      attachTypeTree(formal.tpe)
      method.lookupVar(formal.id.value) match {
        case Some(s) => formal.id.setSymbol(s)
        case None => sys.error("attachFormal: no matching symbol for id")
      }
    }

    def attachVariable(v: VarDecl, scope: Symbol): Unit = {
      val varName = v.id.toString
      scope match {
        case s: ClassSymbol => {
          val symbol = s.lookupVar(varName)
          symbol match {
            case Some(z) => v.setSymbol(z)
            case None => sys.error("attachVariable: No matching variable in class")
          }
        }
        case s: MethodSymbol => {
          val symbol = s.lookupVar(varName)
          symbol match {
            case Some(z) => v.setSymbol(z)
            case None => sys.error("attachVariable: No matching variable in method")
          }
        }
        case s: _ => {
          sys.error("attachVariable: tried to attach with something that shouldn't have variables")
        }
      }
    }





    // main

    // collect symbols
    prog.classes.foreach(classDecl => collectSymbols(classDecl, globalScope))
    collectSymbols(prog.main, globalScope)

    // Step 2: Attach symbols to identifiers (except method calls) in method bodies
    // DEPLOY SYMBOLS
    prog.classes.foreach(classDecl => attachSymbols(classDecl, globalScope))
    attachSymbols(prog.main, globalScope)

    // (Step 3:) Print tree with symbol ids for debugging
    if (ctx.doSymbolIds) {
      //print tree with symbol ids
      val out = Printer.applyWithSymbolIds(prog)
      println(out)
    }
    // Make sure you check all constraints

    prog
  }



}