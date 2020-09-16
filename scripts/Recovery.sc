import ammonite.ops._
import $ivy.`org.typelevel::cats-core:2.0.0`, cats.Id, cats.instances.list._, cats.syntax.traverse._, cats.syntax.either._
import $ivy.`io.circe::circe-parser:0.11.1`, io.circe._, io.circe.syntax._, io.circe.parser._
import $ivy.`com.snowplowanalytics::snowplow-event-recovery-core:0.3.2-rc2`, com.snowplowanalytics.snowplow.event.recovery._, config._, json._
import java.util.concurrent.TimeUnit
import cats.effect.Clock

val load = (path: String) => read ! os.Path(path, base = os.pwd)

// Encode and decode base64
object codecs {
  def base64Encode(str: String) = util.base64.encode(str)
  def base64Decode(str: String) = util.base64.decode(str)
  def thriftDecode(arr: Array[Byte]) = util.thrift.deser(arr)
}

// Test basic operations directly without configuration
// Useful for verifying paths and regexes
object operations {
  def cast(badrow: String, path: String, from: CastType, to: CastType) =
    parse(badrow).flatMap(inspect.cast(from, to)(json.path(path))).leftMap(_.toString).bimap(println, println)

  def replace(badrow: String, path: String, regex: String, newValue: String) =
    parse(badrow).flatMap(inspect.replace(Some(regex), parse(newValue).right.get)(json.path(path))).leftMap(_.toString).bimap(println, println)
}

// Test full json configuration
// Useful for quick testing configurations
object jobs {
  def test(cfg: String, line: String)               = config.load(cfg).flatMap(execute(_)(line)).flatMap(codecs.thriftDecode)
  def testMany(cfg: String, lines: List[String])     = lines.toList.map(test(cfg, _))
  def fromFiles(cfgPath: String, linesPath: String) = testMany(load(cfgPath), load(linesPath).split('\n').toList)

}

// Configuration validation
object configs {
  def validate(cfg: String) = config.validateSchema[Id](cfg, resolverConfig).value.bimap(println, _ => println(s"Config valid"))
  def fromFile(cfgPath: String) = validate(load(cfgPath))

  private[this] val resolverConfig =
    """{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":0,"repositories":[{"name": "Iglu Central","priority": 0,"vendorPrefixes": [ "com.snowplowanalytics" ],"connection": {"http":{"uri":"http://iglucentral.com"}}},{"name":"Priv","priority":0,"vendorPrefixes":["com.snowplowanalytics"],"connection":{"http":{"uri":"http://iglucentral-dev.com.s3-website-us-east-1.amazonaws.com/release/r114"}}}]}}"""

  private[this] implicit val idClock: Clock[Id] = new Clock[Id] {
    final def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS)

    final def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), TimeUnit.NANOSECONDS)
  }
}

