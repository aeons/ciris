import sbt._

object SourceGenerators extends AutoPlugin {
  object autoImport {
    def generateSources(
      sourcesRoot: File,
      testSourcesRoot: File,
      rootPackage: String
    ): Seq[File] = {
      generateConfigValueClasses(sourcesRoot, rootPackage) ++
        generateLoadConfigs(sourcesRoot, rootPackage) ++
        generateLoadConfigsSpec(testSourcesRoot, rootPackage)
    }
  }

  val autoGeneratedNotice: String =
    """
      |/**
      |  * Generated using sbt source generators.
      |  * You should not modify this file directly.
      |  */
    """.stripMargin.trim

  val maximumNumberOfParams: Int = 22

  /**
    * Generates: {{{ A1${sep}A2$sep...A$n$sep }}}
    */
  def typeParams(n: Int, sep: String = ", "): String =
    (1 to n).map(typeParam).mkString(sep)

  /**
    * Generates: {{{ A$n }}}
    */
  def typeParam(n: Int): String = s"A$n"

  /**
    * Generates: {{{ $prefix1${sep}$prefix2$sep...$prefix$n$sep }}}
    */
  def valueParams(n: Int, sep: String = ", ", prefix: String = "a"): String =
    (1 to n).map(i ⇒ valueParam(i, prefix)).mkString(sep)

  /**
    * Generates: {{{ $prefix$n }}}
    */
  def valueParam(n: Int, prefix: String = "a"): String = s"$prefix$n"

  /**
    * Generates: {{{ a1: ${typeName(1)}, a2: ${typeName(2)},..., a$n: ${typeName(n)} }}}
    */
  def args(n: Int, typeName: Int ⇒ String): String =
    (1 to n).map(i ⇒ s"${valueParam(i)}: ${typeName(i)}").mkString(", ")

  def generateLoadConfigs(sourcesRoot: File, rootPackage: String): Seq[File] = {
    val defs =
      (2 until maximumNumberOfParams)
        .map { current ⇒
          val params = typeParams(current)
          val firstArgs = args(current, arg ⇒ s"ConfigValue[${typeParam(arg)}]")

          val loadConfigSecondArgs = s"f: (${typeParams(current)}) => Z"
          val withValuesSecondArgs = s"f: (${typeParams(current)}) => Either[ConfigErrors, Z]"

          Seq(
            s"def loadConfig[$params, Z]($firstArgs)($loadConfigSecondArgs): Either[ConfigErrors, Z] =",
            s"  (${valueParams(current, sep = " append ")}).value.right.map(f.tupled)",
            "",
            s"def withValues[$params, Z]($firstArgs)($withValuesSecondArgs): Either[ConfigErrors, Z] =",
            s"  (${valueParams(current, sep = " append ")}).value.right.flatMap(f.tupled)"
          ).map("  " + _).mkString("\n")
        }
        .mkString("\n\n")

    val content =
      s"""
        |// format: off
        |
        |$autoGeneratedNotice
        |
        |package $rootPackage
        |
        |private [$rootPackage] trait LoadConfigs {
        |  def loadConfig[Z](z: Z): Either[ConfigErrors, Z] =
        |    Right(z)
        |
        |  def loadConfig[A1, Z](a1: ConfigValue[A1])(f: A1 ⇒ Z): Either[ConfigErrors, Z] =
        |    a1.value.fold(error ⇒ Left(ConfigErrors(error)), a1 ⇒ Right(f(a1)))
        |
        |  def withValue[A1, Z](a1: ConfigValue[A1])(f: A1 => Either[ConfigErrors, Z]): Either[ConfigErrors, Z] =
        |   withValues(a1)(f)
        |
        |  def withValues[A1, Z](a1: ConfigValue[A1])(f: A1 => Either[ConfigErrors, Z]): Either[ConfigErrors, Z] =
        |    a1.value.fold(error ⇒ Left(ConfigErrors(error)), f)
        |
        |$defs
        |}
      """.stripMargin.trim + "\n"

    val output = sourcesRoot / rootPackage / "LoadConfigs.scala"
    IO.write(output, content)
    Seq(output)
  }

  def generateConfigValueClasses(sourcesRoot: File, rootPackage: String): Seq[File] = {
    val classes = (2 until maximumNumberOfParams)
      .map { current ⇒
        val next = current + 1
        val nextTypeParam = typeParam(next)
        val currentTypeParams = typeParams(current)

        val defs =
          if (current == maximumNumberOfParams - 1) ""
          else {
            // format: off
            s"""
               |{
               |  def append[$nextTypeParam](next: ConfigValue[$nextTypeParam]): ConfigValue$next[${typeParams(next)}] = {
               |    (value, next.value) match {
               |      case (Right((${valueParams(current)})), Right(${valueParam(next)})) => new ConfigValue$next(Right((${valueParams(next)})))
               |      case (Left(errors), Right(_)) => new ConfigValue$next(Left(errors))
               |      case (Right(_), Left(error)) => new ConfigValue$next(Left(ConfigErrors(error)))
               |      case (Left(errors), Left(error)) => new ConfigValue$next(Left(errors append error))
               |    }
               |  }
               |}
               """.stripMargin.trim
            // format: on
          }

        val signature =
          s"private[$rootPackage] final class ConfigValue$current[$currentTypeParams](val value: Either[ConfigErrors, ($currentTypeParams)])"

        s"$signature$defs"
      }
      .mkString("\n\n")

    val content =
      s"""
         |// format: off
         |
         |$autoGeneratedNotice
         |
         |package $rootPackage
         |
         |$classes
       """.stripMargin.trim + "\n"

    val output = sourcesRoot / rootPackage / "ConfigValues.scala"
    IO.write(output, content)
    Seq(output)
  }

  def generateLoadConfigsSpec(testSourcesRoot: File, rootPackage: String): Seq[File] = {

    def reads(n: Int, from: Int = 1, typeName: String = "String"): String =
      (from to n).map(i ⇒ s"""read[$typeName]("key$i")""").mkString(", ")

    def readsFirstMissing(n: Int): String =
      Seq(
        """read[String]("akey1")""",
        reads(n, from = 2)
      ).filter(_.nonEmpty).mkString(", ")

    def readsLastOneMissing(n: Int): String =
      Seq(
        reads(n - 1),
        s"""read[String]("akey$n")"""
      ).filter(_.nonEmpty).mkString(", ")

    def readsFirstTypeWrong(n: Int): String =
      Seq(
        """read[Int]("key1")""",
        reads(n, from = 2)
      ).filter(_.nonEmpty).mkString(", ")

    def readsLastTypeWrong(n: Int): String =
      Seq(
        reads(n - 1),
        s"""read[Int]("key$n")"""
      ).filter(_.nonEmpty).mkString(", ")

    def readsAllTypesWrong(n: Int): String =
      reads(n, typeName = "Int")

    def identity(n: Int): String =
      s"(${valueParams(n)}) => (${valueParams(n)})"

    def values(n: Int): String =
      (1 to n).map(i ⇒ s""""value$i"""").mkString(", ")

    def testsWithParams(n: Int): String = {
      val tests =
        if (n == 0) {
          s"""
            |"loading 0 keys" should {
            |  "always be able to load" in {
            |    forAll { int: Int =>
            |      loadConfig(int) shouldBe Right(int)
            |    }
            |  }
            |}
          """.stripMargin
        } else {
          val withValueMethods =
            if(n == 1) List("withValues", "withValue")
            else List("withValues")

          // format: off
          s"""
             |"loading $n keys" should {
             |  "be able to load" in {
             |    loadConfig(${reads(n)})(${identity(n)}) shouldBe Right((${values(n)}))
             |  }
             |
             |  "be able to load values" in {
             |    ${withValueMethods.map(_ + s"""(${reads(n)})((${valueParams(n, prefix = "b")}) => loadConfig(${reads(n)})(${identity(n)})) shouldBe Right((${values(n)}))""").mkString("\n    ")}
             |  }
             |
             |  "fail to load if the first one is missing" in {
             |    loadConfig(${readsFirstMissing(n)})(${identity(n)}) shouldBe a[Left[_, _]]
             |  }
             |
             |  "fail to load values if the first one is missing" in {
             |    ${withValueMethods.map(_ + s"""(${readsFirstMissing(n)})((${valueParams(n, prefix = "b")}) => loadConfig(${reads(n)})(${identity(n)})) shouldBe a[Left[_, _]]""").mkString("\n    ")}
             |  }
             |
             |  "fail to load if the last one is missing in" in {
             |    loadConfig(${readsLastOneMissing(n)})(${identity(n)}) shouldBe a[Left[_, _]]
             |  }
             |
             |  "fail to load values if the last one is missing" in {
             |    ${withValueMethods.map(_ + s"""(${readsLastOneMissing(n)})((${valueParams(n, prefix = "b")}) => loadConfig(${reads(n)})(${identity(n)})) shouldBe a[Left[_, _]]""").mkString("\n    ")}
             |  }
             |
             |  "fail to load if the first type is wrong" in {
             |    loadConfig(${readsFirstTypeWrong(n)})(${identity(n)}) shouldBe a[Left[_, _]]
             |  }
             |
             |  "fail to load values if the first type is wrong" in {
             |    ${withValueMethods.map(_ + s"""(${readsFirstTypeWrong(n)})((${valueParams(n, prefix = "b")}) => loadConfig(${reads(n)})(${identity(n)})) shouldBe a[Left[_, _]]""").mkString("\n    ")}
             |  }
             |
             |  "fail to load if the last type is wrong" in {
             |    loadConfig(${readsLastTypeWrong(n)})(${identity(n)}) shouldBe a[Left[_, _]]
             |  }
             |
             |  "fail to load values if the last type is wrong" in {
             |    ${withValueMethods.map(_ + s"""(${readsLastTypeWrong(n)})((${valueParams(n, prefix = "b")}) => loadConfig(${reads(n)})(${identity(n)})) shouldBe a[Left[_, _]]""").mkString("\n    ")}
             |  }
             |
             |  "fail to load and accumulate the errors" in {
             |    loadConfig(${readsAllTypesWrong(n)})(${identity(n)}).left.map(_.size) shouldBe Left($n)
             |  }
             |
             |  "fail to load values and accumulate the errors" in {
             |    ${withValueMethods.map(_ + s"""(${readsAllTypesWrong(n)})((${valueParams(n, prefix = "b")}) => loadConfig(${reads(n)})(${identity(n)})) shouldBe a[Left[_, _]]""").mkString("\n    ")}
             |  }
             |}
           """.stripMargin
          // format: on
        }

      tests.trim.split('\n').map((" " * 6) + _).mkString("\n")
    }

    val tests: String =
      (0 until maximumNumberOfParams)
        .map(testsWithParams)
        .mkString("\n\n")

    val content =
      s"""
        |// format: off
        |
        |$autoGeneratedNotice
        |
        |package $rootPackage
        |
        |final class LoadConfigsSpec extends PropertySpec {
        |  "LoadConfigs" when {
        |    "loading configurations" when {
        |      implicit val source: ConfigSource = sourceWith("key1" → "value1", "key2" → "value2", "key3" → "value3", "key4" → "value4", "key5" → "value5", "key6" → "value6", "key7" → "value7", "key8" → "value8", "key9" → "value9", "key10" → "value10", "key11" → "value11", "key12" → "value12", "key13" → "value13", "key14" → "value14", "key15" → "value15", "key16" → "value16", "key17" → "value17", "key18" → "value18", "key19" → "value19", "key20" → "value20", "key21" → "value21", "key22" → "value22")
        |
        |$tests
        |    }
        |  }
        |}
      """.stripMargin.trim

    val output = testSourcesRoot / rootPackage / "LoadConfigsSpec.scala"
    IO.write(output, content)
    Seq(output)
  }
}
