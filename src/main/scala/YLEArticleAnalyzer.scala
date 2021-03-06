import java.io.{File, FileInputStream, InputStreamReader, PrintWriter}
import java.util
import java.util.{Collections, Locale}

import fi.seco.lexical.combined.CombinedLexicalAnalysisService
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.native.JsonParser._

import scala.jdk.CollectionConverters._
import scala.compat.java8.StreamConverters._

object YLEArticleAnalyzer extends ParallelProcessor {
  
  val la = new CombinedLexicalAnalysisService()
  
  implicit val formats = org.json4s.native.Serialization.formats(NoTypeHints) + new CustomSerializer[HFSTLexicalAnalysisService.Result.WordPart](implicit format => (PartialFunction.empty,
      { case r: HFSTLexicalAnalysisService.Result.WordPart => JObject(JField("lemma",r.getLemma) :: JField("tags",JArray(r.getTags.asScala.toList.map(Extraction.decompose))) :: Nil) })) +
   new CustomSerializer[HFSTLexicalAnalysisService.Result](implicit format => (PartialFunction.empty,
      { case r: HFSTLexicalAnalysisService.Result => JObject(JField("weight",r.getWeight) :: JField("wordParts",JArray(r.getParts.asScala.toList.map(Extraction.decompose))) :: JField("globalTags",JArray(r.getGlobalTags.asScala.toList.map(Extraction.decompose))) :: Nil) })) +
   new CustomSerializer[HFSTLexicalAnalysisService.WordToResults](implicit format => (PartialFunction.empty,
      { case r: HFSTLexicalAnalysisService.WordToResults => JObject(JField("word",r.getWord) :: JField("analysis", JArray(r.getAnalysis.asScala.toList.map(Extraction.decompose))) :: Nil) }))

  val fiLocale = new Locale("fi")
  
  def main(args: Array[String]): Unit = {
    val dest = args.last
    feedAndProcessFedTasksInParallel(() =>
      args.dropRight(1).toIndexedSeq.flatMap(n => getFileTree(new File(n))).parStream.filter(_.getName.endsWith(".json")).forEach(file => {
        parse(new InputStreamReader(new FileInputStream(file)), (p: Parser) => {
          var token = p.nextToken
          while (token != FieldStart("data")) token = p.nextToken
          token = p.nextToken // OpenArr
          token = p.nextToken // OpenObj/CloseArr
          while (token != CloseArr) {
            //assert(token == OpenObj, token)
            val obj = ObjParser.parseObject(p, Some(token))
            val id = (obj \ "id").asInstanceOf[JString].values
            if ((obj \ "language").asInstanceOf[JString].values == "fi")
              addTask(file + "/" + id, () => {
                val json = org.json4s.native.JsonMethods.pretty(org.json4s.native.JsonMethods.render(obj transform {
                  case o: JObject => 
                    val text =  o \ "text"
                    text match {
                      case string: JString => o merge JObject(JField("analyzedText", JArray(la.analyze(string.values, fiLocale, Collections.EMPTY_LIST.asInstanceOf[util.List[String]], false, true, false, 0, 1).asScala.map(Extraction.decompose).toList)))
                      case _ => o
                    }
                }))
                val writer = new PrintWriter(new File(dest+"/"+id+".analysis.json"))
                writer.write(json)
                writer.close()
              })
            token = p.nextToken // OpenObj/CloseArr
          }})
        logger.info("File "+file+" processed.")
      }))
  }
}