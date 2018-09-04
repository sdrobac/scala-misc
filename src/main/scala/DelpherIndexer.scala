import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

import org.apache.lucene.document.{Document, Field, NumericDocValuesField}
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.search.{Sort, SortField}
import org.rogach.scallop._

import scala.io.Source
import scala.language.{postfixOps, reflectiveCalls}
import scala.xml.parsing.XhtmlEntities
import scala.xml.pull._

object DelpherIndexer extends OctavoIndexer {

  private val articles = new AtomicLong
  private val paragraphs = new AtomicLong
  private val sentences = new AtomicLong
  
  class Reuse {
    val sd = new Document // sentence
    val pd = new Document // paragraph
    val ad = new Document // article
    val id = new Document // issue

    val sbi = BreakIterator.getSentenceInstance(new Locale("nl"))
    
    val issueContents = new StringBuilder()
    val articleContents = new StringBuilder()
    
    val issueIDFields = new StringSDVFieldPair("issueID", sd, pd, ad, id)
    val newspaperIDFields = new StringSDVFieldPair("newspaperID", sd, pd, ad, id)
    val newspaperFields = new StringSDVFieldPair("newspaper", sd, pd, ad, id)
    val articleIDFields = new StringSDVFieldPair("articleID", sd, pd, ad)
    val paragraphIDFields = new StringNDVFieldPair("paragraphID", sd, pd)
    val sentenceIDField = new NumericDocValuesField("sentenceID", 0)
    sd.add(sentenceIDField)

    val titleFields = new TextSDVFieldPair("title", sd, pd, ad)

    val dateFields = new IntPointNDVFieldPair("date", sd, pd, ad, id)

    val lengthFields = new IntPointNDVFieldPair("length", sd, pd, ad, id)
    val tokensFields = new IntPointNDVFieldPair("tokens", sd, pd, ad, id)

    var paragraphsInArticle = 0
    var sentencesInArticle = 0
    var paragraphsInIssue = 0
    var sentencesInIssue = 0
    var articlesInIssue = 0
    
    val paragraphsFields = new IntPointNDVFieldPair("paragraphs", ad, id)
    val sentencesFields = new IntPointNDVFieldPair("sentences", pd, ad, id)
    val articlesFields = new IntPointNDVFieldPair("articles", id)

    val textField = new Field("text", "", contentFieldType)
    sd.add(textField)
    pd.add(textField)
    ad.add(textField)
    id.add(textField)
    
    def clearOptionalIssueFields() {
      sentencesInIssue = 0
      paragraphsInIssue = 0
      articlesInIssue = 0
      issueContents.clear()
    }
    def clearOptionalArticleFields() {
      sentencesInArticle = 0
      paragraphsInArticle = 0
      articleContents.clear()
    }
  }
  
  val tld = new ThreadLocal[Reuse] {
    override def initialValue() = new Reuse()
  }
  
  private def readContents(implicit xml: XMLEventReader): String = {
    var break = false
    val content = new StringBuilder()
    while (xml.hasNext && !break) xml.next match {
      case EvElemStart(_,_,_,_) => return null
      case EvText(text) => content.append(text)
      case er: EvEntityRef => XhtmlEntities.entMap.get(er.entity) match {
        case Some(chr) => content.append(chr)
        case _ =>
          logger.warn("Encountered unknown entity "+er.entity)
          content.append('[')
          content.append(er.entity)
          content.append(']')
      }
      case EvComment(comment) if comment == " unknown entity apos; " => content.append('\'')
      case EvComment(comment) if comment.startsWith(" unknown entity") =>
        val entity = content.substring(16, content.length - 2)
        logger.warn("Encountered unknown entity "+entity)
        content.append('[')
        content.append(entity)
        content.append(']')
      case EvComment(comment) =>
        logger.debug("Encountered comment: "+comment)
      case EvElemEnd(_,_) => break = true
    }
    content.toString
  }

  private def processParagraph(paragraph: String)(implicit d: Reuse): Unit = {
    d.sbi.setText(paragraph)
    var start = d.sbi.first()
    var end = d.sbi.next()
    var csentences = 0
    while (end != BreakIterator.DONE) {
      val sentence = paragraph.substring(start,end)
      d.sentenceIDField.setLongValue(sentences.incrementAndGet)
      d.textField.setStringValue(sentence)
      d.lengthFields.setValue(sentence.length)
      d.tokensFields.setValue(getNumberOfTokens(sentence))
      if (siw != null) siw.addDocument(d.sd)
      start = end
      end = d.sbi.next()
      csentences += 1
    }
    d.sentencesFields.setValue(csentences)
    d.sentencesInIssue += csentences
    d.sentencesInArticle += csentences
    d.articleContents.append(paragraph)
    d.articleContents.append("\n\n")
    d.issueContents.append(paragraph)
    d.issueContents.append("\n\n")
    d.paragraphIDFields.setValue(paragraphs.getAndIncrement)
    d.textField.setStringValue(paragraph)
    d.lengthFields.setValue(paragraph.length)
    d.tokensFields.setValue(getNumberOfTokens(paragraph))
    d.paragraphsInArticle += 1
    d.paragraphsInIssue += 1
    piw.addDocument(d.pd)
  }
  
  private def index(file: File): Unit = {
    implicit val d = tld.get
    d.clearOptionalIssueFields()
    val s = Source.fromFile(file)
    implicit var xml = new XMLEventReader(s)
    try {
      var break = false
      while (xml.hasNext && !break) xml.next match {
        case EvElemStart("didl", "Item",attrs,_) =>
          d.issueIDFields.setValue(attrs.asAttrMap("dc:identifier"))
        case EvElemStart("dc","title",_,_) =>
          d.newspaperFields.setValue(readContents)
        case EvElemStart("dc","identifier",attrs,_) if attrs.asAttrMap.get("xsi:type").contains("dcx:PPN") =>
          d.newspaperIDFields.setValue(readContents)
        case EvElemStart("dc","date",_,_) =>
          val dateS = readContents.replaceAllLiterally("-","")
          val sdate = dateS.toInt
          d.dateFields.setValue(sdate)
        case EvElemEnd("didl","Item") => break = true
        case _ =>
      }
      while (xml.hasNext) xml.next() // stupid XMLEventReader.stop() deadlocks and leaves threads hanging
      s.close()
      for (articleFile <- file.getParentFile.listFiles.filter(_.getName.endsWith("_articletext.xml")).sortBy(_.getName)) {
        d.clearOptionalArticleFields()
        d.articleIDFields.setValue(articleFile.getName.replaceAllLiterally("_articletext.xml",""))
        xml = new XMLEventReader(Source.fromFile(articleFile))
        while (xml.hasNext) xml.next match {
          case EvElemStart(_,"title",_,_) => d.titleFields.setValue(readContents)
          case EvElemStart(_,"p",_,_) => processParagraph(readContents)
          case _ =>
        }
        val article = d.articleContents.toString
        d.textField.setStringValue(article)
        d.lengthFields.setValue(article.length)
        d.tokensFields.setValue(getNumberOfTokens(article))
        d.paragraphsFields.setValue(d.paragraphsInArticle)
        d.sentencesFields.setValue(d.sentencesInArticle)
        aiw.addDocument(d.ad)
        d.articlesInIssue += 1
      }
      val issue = d.issueContents.toString
      d.textField.setStringValue(issue)
      d.lengthFields.setValue(issue.length)
      d.tokensFields.setValue(getNumberOfTokens(issue))
      d.paragraphsFields.setValue(d.paragraphsInIssue)
      d.sentencesFields.setValue(d.sentencesInIssue)
      d.articlesFields.setValue(d.articlesInIssue)
      iiw.addDocument(d.id)
      logger.info("File " + file + " processed.")
    } finally {
      while (xml.hasNext) xml.next() // stupid XMLEventReader.stop() deadlocks and leaves threads hanging
    }
  }

  var siw: IndexWriter = _
  var piw: IndexWriter = _
  var aiw: IndexWriter = _
  var iiw: IndexWriter = _

  val ss = new Sort(new SortField("issueID",SortField.Type.STRING), new SortField("articleID",SortField.Type.STRING), new SortField("paragraphID", SortField.Type.LONG), new SortField("sentenceID", SortField.Type.LONG))
  val ps = new Sort(new SortField("issueID",SortField.Type.STRING), new SortField("articleID",SortField.Type.STRING), new SortField("paragraphID", SortField.Type.LONG))
  val as = new Sort(new SortField("issueID",SortField.Type.STRING), new SortField("articleID",SortField.Type.STRING))
  val is = new Sort(new SortField("issueID",SortField.Type.STRING))
  
  val postingsFormats = Seq("text")

  var startYear: Option[Int] = None
  var endYear: Option[Int] = None
  
  def main(args: Array[String]): Unit = {
    val opts = new AOctavoOpts(args) {
      val spostings = opt[String](default = Some("blocktree"))
      val ppostings = opt[String](default = Some("blocktree"))
      val ipostings = opt[String](default = Some("blocktree"))
      val apostings = opt[String](default = Some("blocktree"))
      val noSentenceIndex= opt[Boolean]()
      verify()
    }
    if (!opts.onlyMerge()) {
      if (!opts.noSentenceIndex()) siw = iw(opts.index()+"/sindex",ss,opts.indexMemoryMb() / 4)
      piw = iw(opts.index()+"/pindex",ps,opts.indexMemoryMb() / 4)
      aiw = iw(opts.index()+"/aindex",as,opts.indexMemoryMb() / 4)
      iiw = iw(opts.index()+"/iindex",is,opts.indexMemoryMb() / 4)
      feedAndProcessFedTasksInParallel(() =>
        for (
          d <- opts.directories();
          f <- getFileTree(new File(d))
        ) if (f.getName == "didl.xml") addTask(f.getPath, () => index(f))
      )
    }
    waitForTasks(
      runSequenceInOtherThread(
        () => if (siw != null) close(siw),
        () => if (siw != null) merge(opts.index()+"/sindex", ss,opts.indexMemoryMb() / 4, toCodec(opts.spostings(), postingsFormats))
      ),
      runSequenceInOtherThread(
        () => close(piw), 
        () => merge(opts.index()+"/pindex", ps,opts.indexMemoryMb() / 4, toCodec(opts.ppostings(), postingsFormats))
      ),
      runSequenceInOtherThread(
        () => close(aiw), 
        () => merge(opts.index()+"/aindex", as,opts.indexMemoryMb() / 4, toCodec(opts.apostings(), postingsFormats))
      ),
      runSequenceInOtherThread(
        () => close(iiw), 
        () => merge(opts.index()+"/iindex", is,opts.indexMemoryMb() / 4, toCodec(opts.ipostings(), postingsFormats))
      )
    )
  }
}