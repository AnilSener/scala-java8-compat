/*
 * Copyright (C) 2012-2014 Typesafe Inc. <http://www.typesafe.com>
 */

sealed abstract class Type(val code: Char, val prim: String, val ref: String) {
  def boxed: String = ref
  def unbox(expr: String) = {
    s"scala.runtime.BoxesRunTime.unboxTo${prim.head.toUpper + prim.tail}($expr)"
  }
  def box(expr: String) = {
    s"scala.runtime.BoxesRunTime.boxTo${ref.head.toUpper + ref.tail}($expr)"
  }
}
object Type {
  case object Boolean extends Type('Z', "boolean", "Boolean")
  case object Byte extends Type('B', "byte", "Byte")
  case object Char extends Type('C', "char", "Character")
  case object Short extends Type('S', "short", "Short")
  case object Int extends Type('I', "int", "Integer")
  case object Float extends Type('F', "float", "Float")
  case object Double extends Type('D', "double", "Double")
  case object Long extends Type('J', "long", "Long")
  case object Void extends Type('V', "void", "BoxedUnit")
  case object Object extends Type('L', "Object", "Object")
}

object CodeGen {
  def packaging = "package scala.runtime.java8;"
  case class arity(n: Int) {
    val ns = (1 to n).toList

    def csv(f: Int => String): String = ns.map(f).mkString(", ")

    val tparams = csv("T" + _)

    private def f0Header =
      s"""
      |$copyright
      |
      |$packaging
      |
      |@FunctionalInterface
      |public interface JFunction0<R> extends scala.Function0<R>, java.io.Serializable {
      |    default void $initName() {
      |    };
      |""".stripMargin
    private def f1Header =
      s"""
      |$copyright
      |
      |$packaging
      |
      |@FunctionalInterface
      |public interface JFunction1<T1, R> extends scala.Function1<T1, R>, java.io.Serializable {
      |    default void $initName() {
      |    };
      |
      |    @Override
      |    default <A> scala.Function1<T1, A> andThen(scala.Function1<R, A> g) {
      |        return $function1ImplClass.andThen(this, g);
      |    }
      |
      |    @Override
      |    default <A> scala.Function1<A, R> compose(scala.Function1<A, T1> g) {
      |        return $function1ImplClass.compose(this, g);
      |    }
      |""".stripMargin

    private def fNHeader = {

      val curriedReturn = (1 to n).reverse.foldLeft("R")((x, y) => s"scala.Function1<T$y, $x>")
      val tupledReturn = s"scala.Function1<scala.Tuple${n}<$tparams>, R>"
      val implClass = s"scala.Function$n" + "$class"
      s"""
       |$copyright
       |
       |$packaging
       |
       |@FunctionalInterface
       |public interface JFunction$n<$tparams, R> extends scala.Function$n<$tparams, R>, java.io.Serializable {
       |    default void $initName() {
       |    };
       |
       |    default $curriedReturn curried() {
       |      return $implClass.curried(this);
       |    }
       |
       |    default $tupledReturn tupled() {
       |      return $implClass.tupled(this);
       |    }
       |
       |""".stripMargin
    }

    def fHeader: String =
      n match {
        case 0 => f0Header
        case 1 => f1Header
        case _ => fNHeader
      }

    def pN: String = {
      val vparams = csv(n => s"T$n t$n")
      val vparamRefs = csv(n => s"t$n")
      val parent = "JFunction" + n
      if (n == 0)
        s"""
        |$copyright
        |
        |$packaging
        |
        |import scala.runtime.BoxedUnit;
        |
        |@FunctionalInterface
        |public interface JProcedure0 extends ${parent}<BoxedUnit> {
        |    default void $initName() {
        |    }
        |
        |    void applyVoid();
        |
        |    default BoxedUnit apply() {
        |        applyVoid();
        |        return BoxedUnit.UNIT;
        |    }
        |}
        |""".stripMargin
      else
        s"""
        |$copyright
        |
        |$packaging
        |
        |import scala.runtime.BoxedUnit;
        |
        |@FunctionalInterface
        |public interface JProcedure${n}<${tparams}> extends ${parent}<$tparams, BoxedUnit> {
        |    default void $initName() {
        |    }
        |
        |    void applyVoid($vparams);
        |
        |    default BoxedUnit apply($vparams) {
        |        applyVoid($vparamRefs);
        |        return BoxedUnit.UNIT;
        |    }
        |}
        |""".stripMargin
    }

    def factory: String = {
      val specializedFactories = this.n match {
        case 0 =>
          val tparamNames = function0Spec.map(_._1)

          def specFactory(tps: List[Type]) = {
            val List(r) = tps
            val suffix = specializedSuffix(tparamNames, tps)
            val name = (if (r == Type.Void) "proc" else "func") + "Specialized"
            s"public static scala.Function0<${r.ref}> $name(JFunction0$suffix f) { return f; }"
          }

          for {
            variantTypes <- crossProduct(function0Spec.map(_._2))
          } yield specFactory(variantTypes)
        case 1 =>
          val tparamNames = function1Spec.map(_._1)

          def specFactory(tps: List[Type]) = {
            val List(t, r) = tps
            val suffix = specializedSuffix(tparamNames, tps)
            val name = (if (r == Type.Void) "proc" else "func") + "Specialized"
            s"public static scala.Function1<${t.ref}, ${r.ref}> $name(JFunction1$suffix f) { return f; }"
          }

          for {
            variantTypes <- crossProduct(function1Spec.map(_._2))
          } yield specFactory(variantTypes)
        case 2 =>
          val tparamNames = function2Spec.map(_._1)

          def specFactory(tps: List[Type]) = {
            val List(t1, t2, r) = tps
            val suffix = specializedSuffix(tparamNames, tps)
            val name = (if (r == Type.Void) "proc" else "func") + "Specialized"
            s"public static scala.Function2<${t1.ref}, ${t2.ref}, ${r.ref}> $name(JFunction2$suffix f) { return f; }"
          }

          for {
            variantTypes <- crossProduct(function2Spec.map(_._2))
          } yield specFactory(variantTypes)
        case _ =>
          Nil
      }
      if (n == 0)
        s"""
        |public static <R> scala.Function$n<R> func(JFunction$n<R> f) { return f; }
        |public static scala.Function$n<BoxedUnit> proc(JProcedure$n p) { return p; }
        |${specializedFactories.mkString("\n")}
        |""".stripMargin.trim
      else
        s"""
        |public static <$tparams, R> scala.Function$n<$tparams, R> func(JFunction$n<$tparams, R> f) { return f; }
        |public static <$tparams> scala.Function$n<$tparams, BoxedUnit> proc(JProcedure$n<$tparams> p) { return p; }
        |${specializedFactories.mkString("\n")}
        |""".stripMargin.trim
    }

    def accept: String = {
      val targs = csv(_ => "String")
      val vargs = csv("\"" + _ + "\"")
      s"""
      |static <T> T acceptFunction$n(scala.Function$n<$targs, T> f) {
      |    return f.apply($vargs);
      |}
      |static void acceptFunction${n}Unit(scala.Function$n<$targs, scala.runtime.BoxedUnit> f) {
      |    f.apply($vargs);
      |}
      |""".stripMargin
    }
  }

  def f0Specialized(tps: List[Type]): (String, String) = {
    val tparamNames = function0Spec.map(_._1)
    val suffix = specializedSuffix(tparamNames, tps)
    val List(r) = tps
    val applyMethodBody = if (r == Type.Void) s"apply$suffix(); return scala.runtime.BoxedUnit.UNIT;"
      else s"return ${r.box(s"apply$suffix()")};"
    val code = s"""
     |$copyright
     |
     |$packaging
     |
     |@FunctionalInterface
     |public interface JFunction0$suffix extends JFunction0 {
     |    ${r.prim} apply$suffix();
     |
     |    default Object apply() { $applyMethodBody }
     |}
     |""".stripMargin
    (s"JFunction0$suffix", code)
  }


  def f1Specialized(tps: List[Type]): (String, String) = {
    val tparamNames = function1Spec.map(_._1)
    val suffix = specializedSuffix(tparamNames, tps)
    val List(t, r) = tps
    val applyMethodBody = if (r == Type.Void) s"apply$suffix(${t.unbox("t")}); return scala.runtime.BoxedUnit.UNIT;"
      else s"return ${r.box(s"apply$suffix(${t.unbox("t")})")};"
    val code = s"""
     |$copyright
     |
     |$packaging
     |
     |@FunctionalInterface
     |public interface JFunction1$suffix extends JFunction1 {
     |    ${r.prim} apply$suffix(${t.prim} v1);
     |
     |    default Object apply(Object t) { $applyMethodBody }
     |}
     |""".stripMargin
    (s"JFunction1$suffix", code)
  }

  def f2Specialized(tps: List[Type]): (String, String) = {
    val tparamNames = function2Spec.map(_._1)
    val suffix = specializedSuffix(tparamNames, tps)
    val List(t1, t2, r) = tps
    val applyMethodBody = if (r == Type.Void) s"apply$suffix(${t1.unbox("v1")}, ${t2.unbox("v2")}); return scala.runtime.BoxedUnit.UNIT;"
      else s"return ${r.box(s"apply$suffix(${t1.unbox("v1")}, ${t2.unbox("v2")}")});"
    val code = s"""
     |$copyright
     |
     |$packaging
     |
     |@FunctionalInterface
     |public interface JFunction2$suffix extends JFunction2 {
     |    ${r.prim} apply$suffix(${t1.prim} v1, ${t2.prim} v2);
     |
     |    default Object apply(Object v1, Object v2) { $applyMethodBody }
     |}
     |""".stripMargin
    (s"JFunction2$suffix", code)
  }

  private val initName = "$init$"
  private val function1ImplClass = "scala.Function1$class"
  private val copyright =
    """
    |/*
    | * Copyright (C) 2012-2015 Typesafe Inc. <http://www.typesafe.com>
    | */""".stripMargin.trim

  private def function0SpecMethods = {
    val apply = specialized("apply", function0Spec) {
      case (name, List(r)) =>
        val applyCall = s"apply()"
        def body = if (r == Type.Void) applyCall else s"return ${r.unbox(applyCall)}"
        s"""
        |default ${r.prim} $name() {
        |    $body;
        |}
        |""".stripMargin.trim
    }
    indent(apply)
  }

  private val function0Spec = {
    val rs = List(Type.Void, Type.Byte, Type.Short, Type.Int, Type.Long, Type.Char, Type.Float, Type.Double, Type.Boolean)
    List("R" -> rs)
  }
  private val function1Spec = {
    val ts = List(Type.Int, Type.Long, Type.Float, Type.Double)
    val rs = List(Type.Void, Type.Boolean, Type.Int, Type.Float, Type.Long, Type.Double)
    List("T1" -> ts, "R" -> rs)
  }
  private val function2Spec = {
    val ts = List(Type.Int, Type.Long, Type.Double)
    val rs = List(Type.Void, Type.Boolean, Type.Int, Type.Float, Type.Long, Type.Double)
    List("T1" -> ts, "T2" -> ts, "R" -> rs)
  }

  private def function1SpecMethods = {
    val apply = specialized("apply", function1Spec) {
      case (name, List(t1, r)) =>
        val applyCall = s"apply((T1) ${t1.box("v1")})"
        def body = if (r == Type.Void) applyCall else s"return ${r.unbox(applyCall)}"
        s"""
        |default ${r.prim} $name(${t1.prim} v1) {
        |    $body;
        |}
        |""".stripMargin.trim
    }
    indent(List(apply).mkString("\n\n"))
  }

  private def function2SpecMethods = {
    val apply = specialized("apply", function2Spec) {
      case (name, List(t1, t2, r)) =>
        val applyCall = s"apply((T1) ${t1.box("v1")}, (T2) ${t2.box("v2")})"
        def body = if (r == Type.Void) applyCall else s"return ${r.unbox(applyCall)}"

        s"""
        |default ${r.prim} $name(${t1.prim} v1, ${t2.prim} v2) {
        |    $body;
        |}
        |""".stripMargin.trim
    }
    indent(List(apply).mkString("\n\n"))
  }

  def specializedSuffix(tparamNames: List[String], tps: List[Type]): String = {
    val sorted = (tps zip tparamNames).sortBy(_._2).map(_._1) // as per scalac, sort by tparam name before assembling the code
    val code = sorted.map(_.code).mkString
    "$mc" + code + "$sp"
  }

  private def specialized(name: String, tps: List[(String, List[Type])])(f: (String, List[Type]) => String): String = {
    val tparamNames = tps.map(_._1)
    val ms = for {
      variantTypes <- crossProduct(tps.map(_._2))
      specName = name + specializedSuffix(tparamNames, variantTypes)
    } yield f(specName, variantTypes)
    ms.mkString("\n")
  }

  def crossProduct[A](input: List[List[A]]): List[List[A]] = input match {
    case Nil => Nil
    case head :: Nil => head.map(_ :: Nil)
    case head :: tail => for (elem <- head; sub <- crossProduct(tail)) yield elem :: sub
  }

  def fN(n: Int) = {
    val header = arity(n).fHeader
    val specializedVariants = n match {
      case 0 => function0SpecMethods
      case 1 => function1SpecMethods
      case 2 => function2SpecMethods
      case x => ""
    }
    val trailer = "\n}\n"
    List(header, specializedVariants, trailer).mkString
  }

  def specializedF0: List[(String, String)] = {
    val tparamNames = function0Spec.map(_._1)
    for {
      variantTypes <- crossProduct(function0Spec.map(_._2))
    } yield f0Specialized(variantTypes)
  }
  def specializedF1: List[(String, String)] = {
    val tparamNames = function1Spec.map(_._1)
    for {
      variantTypes <- crossProduct(function1Spec.map(_._2))
    } yield f1Specialized(variantTypes)
  }
  def specializedF2: List[(String, String)] = {
    val tparamNames = function2Spec.map(_._1)
    for {
      variantTypes <- crossProduct(function2Spec.map(_._2))
    } yield f2Specialized(variantTypes)
  }

  def pN(n: Int) = arity(n).pN

  def factory: String = {
    val ms = (0 to 22).map(n => arity(n).factory).mkString("\n")
    s"""
    |$copyright
    |
    |$packaging
    |
    |import scala.runtime.BoxedUnit;
    |
    |public final class JFunction {
    |    private JFunction() {}
    |${indent(ms)}
    |}
    |
    |""".stripMargin
  }

  def testApi: String = {
    s"""
    |$copyright
    |
    |$packaging
    |
    |final class TestAPI {
    |${(1 to 22).map(n => arity(n).accept).map(indent).mkString("\n\n")}
    |}
    |""".stripMargin
  }

  def indent(s: String) = s.linesIterator.map("    " + _).mkString("\n")
}
