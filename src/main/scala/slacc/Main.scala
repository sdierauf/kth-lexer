package slacc

import utils._
import java.io.{PrintWriter, File}
import scala.collection.JavaConversions._

import lexer._
import ast._

object Main {

  def getFileTree(f: File): Stream[File] =
    f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree)
    else Stream.empty)

  def processOptions(args: Array[String]): Context = {

    val reporter = new Reporter()
    var ctx = Context(reporter = reporter)

    def processOption(args: List[String]): Unit = args match {
      case "--help" :: args =>
        ctx = ctx.copy(doHelp = true)
        processOption(args)

      case "--tokens" :: args =>
        ctx = ctx.copy(doTokens = true)
        processOption(args)

      case "--print" :: args =>
        ctx = ctx.copy(doPrintMain = true)
        processOption(args)

      case "--ast" :: args =>
        ctx = ctx.copy(doAST = true)
        processOption(args)

      case "-d" :: out :: args =>
        ctx = ctx.copy(outDir = Some(new File(out)))
        processOption(args)

      case "--ppt" :: args =>
        ctx = ctx.copy(testPrint = true)
        processOption(args)

      case "--tap" :: args =>
        ctx = ctx.copy(doTokens = true, doPrintMain = true, doAST = true)
        processOption(args)

      case "--testAst" :: args =>
        ctx = ctx.copy(testAst = true)
        processOption(args)

      case f :: args =>
        ctx = ctx.copy(files = new File(f) :: ctx.files)
        processOption(args)

      case Nil =>
    }

    processOption(args.toList)

    if (ctx.doHelp) {
      displayHelp()
      sys.exit(0)
    }

    if (ctx.files.size != 1) {
      reporter.fatal("Exactly one file expected, "+ctx.files.size+" file(s) given.")
    }

    ctx
  }

  def displayHelp() {
    println("Usage: ./slacc [options] <file>")
    println("Options include:")
    println(" --help        displays this help")
    println(" --tokens      displays the list of tokens")
    println(" --print       pretty-prints the program")
    println(" --ast         displays the AST")
    println(" --tap         displays list of tokens, then ast, then pretty prints")
    println(" -d <outdir>   generates class files in the specified directory")
    println(" --ppt         tests that print(parse(P)) = print(parse(print(parse(P))))")
  }

  def main(args: Array[String]) {
    var ctx = processOptions(args) // changed from val to var for ppt

    if (ctx.doTokens && ctx.doPrintMain && ctx.doAST) {
      val iter = Lexer.run(ctx)(ctx.files.head)
      while (iter.hasNext) {
        val n = iter.next()
        println(n+"("+n.line+":"+n.col+")")
      }
      println()
      val pipeline = Lexer andThen Parser
      val ast = pipeline.run(ctx)(ctx.files.head)
      println(ast)
      println()
      println(Printer(ast))
    } else if (ctx.doTokens) {
      val iter = Lexer.run(ctx)(ctx.files.head)
      while (iter.hasNext) {
        val n = iter.next()
        println(n+"("+n.line+":"+n.col+")")
      }
    } else if (ctx.doPrintMain) {
      val pipeline = Lexer andThen Parser
      val ast = pipeline.run(ctx)(ctx.files.head)
      println(Printer(ast))
    } else if (ctx.doAST) {
      val pipeline = Lexer andThen Parser
      val ast = pipeline.run(ctx)(ctx.files.head)
      println(ast)
    } else if (ctx.testPrint) {
      // test print(parse(P)) = print(parse(print(parse(P)))) holds
      val pipeline = Lexer andThen Parser
      val ast = pipeline.run(ctx)(ctx.files.head)
      val program = Printer(ast)
      new PrintWriter("ppt_lhs.slacc") {
        write(program); close
      }
      ctx = ctx.copy(files = new File("ppt_lhs.slacc") :: ctx.files)
      val rhsPipe = Lexer andThen Parser
      val rhsAst = pipeline.run(ctx)(ctx.files.head)
      val rhsProgram = Printer(rhsAst)
      // now compare the strings
      if (rhsProgram == program) {
        print("print(parse(P)) = print(parse(print(parse(P)))) holds.")
      } else {
        println("Test failed.")
        println(program diff rhsProgram)
      }
    } else if (ctx.testAst) {
      // try all valids
      println()
      println("========== Running tests ==========")
      println()
      getFileTree(ctx.files.head).filter(_.getName.endsWith(".slac")).foreach(f => {
        println()
        println("Running tests for: " + f.getName())
        println("------------------")
        println()
        val pipeline = Lexer andThen Parser
        val ast = pipeline.run(ctx)(f)
        val validAst = new File(f.getAbsolutePath() + ".ast")
        if (ast.toString == validAst.toString()) {
          println("PASS: " + f.getCanonicalPath() + " was parsed correctly")
        } else {
          println("FAIL: " + f.getCanonicalFile() + " was parsed incorrectly")
        }
      })
    } else if (ctx.testParse) {
      println()
      println("========== Running tests ==========")
      println()
      getFileTree(ctx.files.head).filter(_.getName.endsWith(".slac")).foreach(f => {
        println()
        println("Running tests for: " + f.getName())
        println("------------------")
        println()
        val pipeline = Lexer andThen Parser
        val ast = pipeline.run(ctx)(f)
        val validAst = new File(f.getAbsolutePath() + ".ast")
        if (ast.toString == validAst.toString()) {
          println("PASS: " + f.getCanonicalPath() + " was parsed correctly")
        } else {
          println("FAIL: " + f.getCanonicalFile() + " was parsed incorrectly")
        }
      })
    } else {
      ???
    }
  }
}
