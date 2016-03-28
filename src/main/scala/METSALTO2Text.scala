import com.bizo.mighty.csv.CSVReader
import java.net.URLEncoder
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.RDFDataMgr
import java.io.FileOutputStream
import com.bizo.mighty.csv.CSVDictReader
import com.bizo.mighty.csv.CSVReaderSettings
import scala.io.Source
import scala.xml.pull.XMLEventReader
import scala.xml.pull.EvElemStart
import scala.xml.pull.EvText
import scala.xml.pull.EvElemEnd
import scala.xml.MetaData
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import java.io.PrintWriter
import java.io.File

object METSALTO2Text {
  
      
  def get(key: String)(implicit attrs: MetaData): Option[String] = {
    if (attrs(key)!=null && attrs(key)(0).text!="") Some(attrs(key)(0).text.trim)
    else None
  }
  
  def attrsToString(attrs:MetaData) = {
    attrs.length match {
      case 0 => ""
      case _ => attrs.map( (m:MetaData) => " " + m.key + "='" + m.value +"'" ).reduceLeft(_+_)
    }
  }
  
  def getFileTree(f: File): Stream[File] =
    f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree) 
      else Stream.empty)
      
  def main(args: Array[String]): Unit = {
    for (metsFile <- getFileTree(new File(args(0))) if (metsFile.getName().endsWith("_mets.xml"))) {
      println("Processing: "+metsFile.getParent)
      new File(metsFile.getParent+"/extracted").mkdir()
      val prefix = metsFile.getParent+"/extracted/"+metsFile.getParentFile.getName+'_'
      val textBlocks = new HashMap[String,String]
      val composedBlocks = new HashMap[String,HashSet[String]]
      var break = false
      for (file <- new File(metsFile.getParent+"/alto").listFiles) {
        val xml = new XMLEventReader(Source.fromFile(file,"UTF-8"))
        var composedBlock: Option[HashSet[String]] = None
        while (xml.hasNext) xml.next match {
          case EvElemStart(_,"ComposedBlock",attrs,_) => 
            composedBlock = Some(new HashSet[String])
            composedBlocks.put(attrs("ID")(0).text,composedBlock.get)
          case EvElemEnd(_,"ComposedBlock") =>
            composedBlock = None 
          case EvElemStart(_,"TextBlock",attrs,_) => 
            var text = ""
            val textBlock = attrs("ID")(0).text 
            composedBlock.foreach(_+=textBlock)
            break = false
            while (xml.hasNext && !break) xml.next match {
              case EvElemStart(_,"String",attrs,_) if (attrs("SUBS_TYPE")!=null && attrs("SUBS_TYPE")(0).text=="HypPart1") => text+=attrs("SUBS_CONTENT")(0).text
              case EvElemStart(_,"String",attrs,_) if (attrs("SUBS_TYPE")!=null && attrs("SUBS_TYPE")(0).text=="HypPart2") => 
              case EvElemStart(_,"String",attrs,_) => text+=attrs("CONTENT")(0).text
              case EvElemStart(_,"SP",attrs,_) => text+=" "
              case EvElemEnd(_,"TextLine") => text+="\n"
              case EvElemEnd(_,"TextBlock") => break = true
              case _ =>
            }
            textBlocks.put(textBlock,text)
          case _ =>
        }
      }
      val xml = new XMLEventReader(Source.fromFile(metsFile,"UTF-8"))
      val articleMetadata: HashMap[String,String] = new HashMap
      var advertisements = 0
      var titleSections = 0
      while (xml.hasNext) xml.next match {
        case EvElemStart(_,"dmdSec", attrs, _) if attrs("ID")(0).text.startsWith("MODSMD_ARTICLE") =>
          val article = attrs("ID")(0).text
          break = false
          while (xml.hasNext && !break) xml.next match {
            case EvElemStart(_,"mods",_,_) => break = true
            case _ => 
          }
          break = false
          var indent = ""
          var metadata = ""
          while (xml.hasNext && !break) xml.next match {
            case EvElemStart(pre, label, attrs, scope) =>
              metadata+= indent + "<" + label + attrsToString(attrs) + ">\n"
              indent += "  "
            case EvText(text) => if (text.trim!="") metadata += indent + text.trim + "\n"
            case EvElemEnd(_,"mods") => break = true
            case EvElemEnd(_, label) => 
              indent = indent.substring(0,indent.length-2)
              metadata+=indent + "</"+label+">\n" 
          }
          articleMetadata.put(article,metadata)
        case EvElemStart(_,"div", attrs, _) if (attrs("TYPE")(0).text=="ARTICLE" || attrs("TYPE")(0).text=="ADVERTISEMENT"|| attrs("TYPE")(0).text=="TITLE_SECTION") => 
          val atype = attrs("TYPE")(0).text.toLowerCase
          val articleNumber = if (atype=="advertisement") {
            advertisements += 1          
            ""+advertisements
          } else if (atype=="title_section") {
            titleSections += 1          
            ""+titleSections
          } else attrs("DMDID")(0).text.substring(14)
          val pw = new PrintWriter(new File(prefix+atype+'_'+articleNumber+".txt"))
          var depth = 1
          val pages = new HashSet[Int]
          while (xml.hasNext && depth!=0) xml.next match {
            case EvElemStart(_,"div",attrs,_) => 
              depth += 1
              attrs("TYPE")(0).text match {
                case "TITLE" => pw.append("# ")
                case "OVERLINE" => 
                  val breakDepth = depth - 1
                  while (xml.hasNext && depth!=breakDepth) xml.next match {
                    case EvElemEnd(_,"div") => depth -= 1
                    case _ => 
                  }
                case _ => 
              }
            case EvElemStart(_,"area",attrs,_) => {
              val areaId = attrs("BEGIN")(0).text
              if (textBlocks.contains(areaId)) {
                pages.add(Integer.parseInt(areaId.substring(1,areaId.indexOf('_'))))
                pw.println(textBlocks.remove(areaId).get)
              }
              else for (block <- composedBlocks.remove(areaId).get) {
                pages.add(Integer.parseInt(block.substring(1,block.indexOf('_'))))
                pw.println(textBlocks.remove(block).get)
              }
            }
            case EvElemEnd(_,"div") => depth -= 1
            case _ => 
          }
          pw.close()
          if (atype == "article" && articleMetadata.contains(attrs("DMDID")(0).text)) {
            val pw = new PrintWriter(new File(prefix+atype+"_"+articleNumber+"_metadata.xml"))
            pw.append("<metadata>\n")
            pw.append(articleMetadata(attrs("DMDID")(0).text))
            pw.append("<pages>"+pages.toSeq.sorted.mkString(",")+"</pages>\n")
            pw.append("</metadata>\n")
            pw.close()
          } else {
            val pw = new PrintWriter(new File(prefix+atype+"_"+articleNumber+"_metadata.xml"))
            pw.append("<metadata>\n")
            pw.append("<pages>"+pages.toSeq.sorted.mkString(",")+"</pages>\n")
            pw.append("</metadata>\n")
            pw.close()
          }
        case _ =>  
      }
      var blocks = 0
      for (block <- composedBlocks.values) {
        val pages = new HashSet[Int]
        blocks += 1
        var pw = new PrintWriter(new File(prefix+"other_texts_"+blocks+"_metadata.xml"))
        pw.println("<metadata>\n<blocks>"+block.toSeq.sorted.mkString(",")+"</blocks>\n</metadata>")
        pw.close()
        pw = new PrintWriter(new File(prefix+"other_texts_"+blocks+".txt"))
        for (textBlock <- block) {
          pages.add(Integer.parseInt(textBlock.substring(1,textBlock.indexOf('_'))))
          pw.println(textBlocks.remove(textBlock).get)
        }
        pw.close()
      }
      if (!textBlocks.isEmpty) {
        val pageBlocks = new HashMap[Int,HashSet[String]]
        for (textBlock <- textBlocks.keys) pageBlocks.getOrElseUpdate(Integer.parseInt(textBlock.substring(1,textBlock.indexOf('_'))),new HashSet[String]).add(textBlock)
        for (page <- pageBlocks.keys) {
          var pw = new PrintWriter(new File(prefix+"other_texts_page_"+page+"_metadata.xml"))
          pw.println("<metadata>\n<blocks>"+pageBlocks(page).toSeq.sorted.mkString(",")+"</blocks>\n</metadata>")
          pw.close()
          pw = new PrintWriter(new File(prefix+"other_texts_page_"+page+".txt"))
          for (text <- pageBlocks(page).toSeq.sorted.map(textBlocks(_))) pw.println(text)
          pw.close()
        }
      }
    }
  }
}