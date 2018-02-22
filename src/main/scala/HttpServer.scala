import java.io.{File, RandomAccessFile}
import java.net.{InetSocketAddress, SocketAddress}
import java.nio.ByteBuffer
import java.nio.channels._
import java.util
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.util.control.NonFatal

trait Selectable {
  def handleSelect(key: SelectionKey, selector: Selector): Unit
}

class Logger {
  def trace(message: String): Unit = {
    print("[TRACE] ")
    println(message)
  }
  def debug(message: String): Unit = {
    print("[DEBUG] ")
    println(message)
  }
  def info(message: String): Unit = {
    print("[INFO] ")
    println(message)
  }
  def error(message: String): Unit = {
    print("[ERROR] ")
    println(message)
  }
  def error(message: String, exception: Throwable): Unit = {
    print("[ERROR] ")
    println(message)
    exception.printStackTrace()
  }
}
trait Logging {
  lazy val logger = new Logger
}



object HttpServer {
  def main(args: Array[String]): Unit = {
    val handler = new ComplexRequestHandler
    val requestHandler = handler
    val server = new HttpServer(Selector.open(), new InetSocketAddress(8080), requestHandler)
    server.run()
  }
}

class HttpServer(selector: Selector, listenSocketAddress: SocketAddress, requestHandler: RequestHandler) extends Logging {
  private val bufferPool = new ObjectPool[ByteBuffer](10, ByteBuffer.allocateDirect(1024))

  private val serverSocketChannel = ServerSocketChannel.open()

  private val running = new AtomicBoolean(true)

  def run(): Unit = {
    logger.info(s"Starting HTTP server on $listenSocketAddress")

    serverSocketChannel.bind(listenSocketAddress)
    serverSocketChannel.configureBlocking(false)
    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT)

    while (running.get()) {
      if (selector.select() > 0) {
        val keys = selector.selectedKeys()

        try {
          val it = keys.iterator
          while (it.hasNext) {
            val key = it.next()
            if (key.isAcceptable) {
              val socketChannel = serverSocketChannel.accept()
              logger.debug(s"Accepting connection from ${socketChannel.getRemoteAddress}")
              new HttpClientConnection(selector, socketChannel, bufferPool, requestHandler)
            }
            else {
              try {
                key.attachment.asInstanceOf[Selectable].handleSelect(key, selector)
              }
              catch {
                case NonFatal(e) => logger.error(s"Failed to handle select for channel ${key.channel}", e)
              }
            }
          }
        }
        finally {
          keys.clear()
        }
      }
    }
  }
}





sealed case class HttpMethod(method: String)
object HttpMethod {
  val GET = HttpMethod("GET")
  val POST = HttpMethod("POST")
}

class HttpRequest {
  var buffer: ByteBuffer = _
  var method: HttpMethod = _
  var uriStart: Int = _
  var uriEnd: Int = _
  var httpVersionMajor: Int = _
  var httpVersionMinor: Int = _
  var firstHeader: Header = _
  var lastHeader: Header = _

  def uri: String = {
    val sb = new mutable.StringBuilder(uriEnd - uriStart)
    for (i <- uriStart until uriEnd) {
      sb.append(buffer.get(i).toChar)
    }
    sb.toString
  }
}

class Header {
  var buffer: ByteBuffer = _
  var start: Int = _
  var end: Int = _

  var next: Header = _
  var prev: Header = _

  override def toString: String = {
    val sb = new mutable.StringBuilder(end - start)
    for (i <- start until end) {
      sb.append(buffer.get(i).toChar)
    }
    sb.toString
  }
}

trait RequestHandler {
  def handleRequest(implicit httpRequest: HttpRequest, clientConnection: HttpClientConnection): Unit
}

class HttpClientConnection(selector: Selector,
                           socketChannel: SocketChannel,
                           bufferPool: ObjectPool[ByteBuffer],
                           requestHandler: RequestHandler) extends Selectable {
  private val logger = new ConnectionLogger

  socketChannel.configureBlocking(false)
  socketChannel.register(selector, SelectionKey.OP_READ, this)

  private val requestBuffer = bufferPool.acquire()

  def handleConnectionClosed(key: SelectionKey): Unit = {
    key.cancel()
    socketChannel.close()
  }


  trait ReadingState { def handleBytesRead(startPos: Int, endPos: Int): ReadingState }
  trait LineReader { def handleLine(startPos: Int, endPos: Int): LineReader }

  private val awaitingCr = new AwaitingCr
  private val awaitingLf = new AwaitingLf

  private val requestLineReader = new RequestLineReader
  private val headerLineReader = new HeaderLineReader

  private var state: ReadingState = awaitingCr
  private var lineReader: LineReader = requestLineReader



  private val request = new HttpRequest


  override def handleSelect(key: SelectionKey, selector: Selector): Unit = {
    val startPos = requestBuffer.obj.position

    logger.trace(requestBuffer.obj.toString)
    val bytesRead = socketChannel.read(requestBuffer.obj)
    logger.trace(s"handleSelect bytesRead = $bytesRead")

    if (bytesRead < 0) {
      handleConnectionClosed(key)
    }
    else if (bytesRead > 0) {
      requestBuffer.obj.flip()
      state = state.handleBytesRead(startPos = startPos, endPos = startPos + bytesRead)
      // TODO: check if state is 'new' and reset buffer if so
//      requestBuffer.obj.flip()

      requestBuffer.obj.clear()
    }
  }

  class AwaitingCr extends ReadingState {
    private var lineStartPos = 0
    def init(lineStartPos: Int): Unit = this.lineStartPos = lineStartPos

    override def handleBytesRead(startPos: Int, endPos: Int): ReadingState = {
      logger.trace(s"handleBytesRead(startPos = $startPos, endPos = $endPos)")

      @tailrec def loop(index: Int): ReadingState = {

        if (index < endPos) {

          val ch = requestBuffer.obj.get(index)
          print(ch.toChar)

          if (requestBuffer.obj.get(index) == '\r') {
//            logger.debug("\\r")

            awaitingLf.init(lineStartPos = lineStartPos)
            awaitingLf.handleBytesRead(startPos = index+1, endPos = endPos)
          }
          else loop(index + 1)
        }
        else this
      }
      loop(startPos)
    }
  }

  class AwaitingLf extends ReadingState {
    private var lineStartPos = 0
    def init(lineStartPos: Int): Unit = this.lineStartPos = lineStartPos

    override def handleBytesRead(startPos: Int, endPos: Int): ReadingState = {
      @tailrec def loop(index: Int): ReadingState = {
        if (index < endPos) {

          print(requestBuffer.obj.get(index).toChar)

          if (requestBuffer.obj.get(index) == '\n') {
            lineReader = lineReader.handleLine(startPos = lineStartPos, endPos = index-1)

            // TODO: check if lineReader is 'new' request, if not, need additional buffer

            awaitingCr.init(if (index+1 == requestBuffer.obj.limit) 0 else index+1)
            awaitingCr.handleBytesRead(index+1, endPos)
          }
          else loop(index + 1)
        }
        else this
      }
      loop(startPos)
    }
  }

  class RequestLineReader extends LineReader {

    override def handleLine(lineStartPos: Int, lineEndPos: Int): LineReader = {

      request.buffer = requestBuffer.obj
      val ch = requestBuffer.obj.get(lineStartPos)
      (ch: @switch) match {
        case 'G' =>
          assert(requestBuffer.obj.get(lineStartPos+1) == 'E')
          assert(requestBuffer.obj.get(lineStartPos+2) == 'T')
          assert(requestBuffer.obj.get(lineStartPos+3) == ' ')
          request.method = HttpMethod.GET
          request.uriStart = lineStartPos + 4
          expectRequestUri(lineEndPos)
        case 'P' =>
          assert(requestBuffer.obj.get(lineStartPos+1) == 'O')
          assert(requestBuffer.obj.get(lineStartPos+2) == 'S')
          assert(requestBuffer.obj.get(lineStartPos+3) == 'T')
          assert(requestBuffer.obj.get(lineStartPos+4) == ' ')
          request.method = HttpMethod.POST
          request.uriStart = lineStartPos + 5
          expectRequestUri(lineEndPos)
      }

      headerLineReader
    }

    private def expectRequestUri(lineEndPos: Int): Unit = {
      @tailrec def loop(index: Int): Unit = {
        if (index < lineEndPos) {
          if (requestBuffer.obj.get(index) == ' ') {
            request.uriEnd = index
            expectVersion(lineEndPos)
          }
          else loop(index + 1)
        }
        else sys.error("Invalid request") // TODO: send error code
      }
      loop(request.uriStart)
    }

    private def expectVersion(lineEndPos: Int): Unit = {
      assert(requestBuffer.obj.get(request.uriEnd+1) == 'H')
      assert(requestBuffer.obj.get(request.uriEnd+2) == 'T')
      assert(requestBuffer.obj.get(request.uriEnd+3) == 'T')
      assert(requestBuffer.obj.get(request.uriEnd+4) == 'P')
      assert(requestBuffer.obj.get(request.uriEnd+5) == '/')
      request.httpVersionMajor = requestBuffer.obj.get(request.uriEnd+5).toChar - '0'
      assert(requestBuffer.obj.get(request.uriEnd+7) == '.')
      request.httpVersionMinor = requestBuffer.obj.get(request.uriEnd+8).toChar - '0'



//      logger.debug(request.uri)
//
//      logger.debug("After HTTP version:")
//      for (i <- request.uriEnd+9 until lineEndPos) {
//        print(requestBuffer.obj.get(i).toChar)
//      }
//      println()
//      logger.debug("^")

      assert(lineEndPos == request.uriEnd+9, s"$lineEndPos != ${request.uriEnd+9}")

    }
  }

  class HeaderLineReader extends LineReader {

    override def handleLine(startPos: Int, endPos: Int): LineReader = {
      if (endPos == startPos) {
        // end of header
        //

        // assume there's no body
        requestHandler.handleRequest(request, HttpClientConnection.this)
        requestLineReader
      }
      else {
        // TODO: pool
        val header = new Header
        header.buffer = requestBuffer.obj
        header.start = startPos
        header.end = endPos

        header.prev = request.lastHeader
        if (request.lastHeader != null) request.lastHeader.next = header
        if (request.firstHeader == null) request.firstHeader = header
        request.lastHeader = header

        headerLineReader
      }
    }
  }


  // ********************
  // writing logic
  // ********************


  // TODO: accept pooled buffer
  def write(outputBuffer: ByteBuffer): Boolean = {
    socketChannel.write(outputBuffer)
    !outputBuffer.hasRemaining
  }

  def transferFrom(fromChannel: FileChannel): Boolean = {
    val fileSize = fromChannel.size
    val bytesTransferred = fromChannel.transferTo(0, fileSize, socketChannel)
    bytesTransferred < fileSize
  }

  def write(value: String): Boolean = write(ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))






  class ConnectionLogger {
    def trace(message: String): Unit = {
      print("[TRACE] ")
      print(socketChannel.getRemoteAddress)
      print(": ")
      println(message)
    }
    def debug(message: String): Unit = {
      print("[DEBUG] ")
      print(socketChannel.getRemoteAddress)
      print(": ")
      println(message)
    }
    def info(message: String): Unit = {
      print("[INFO] ")
      print(socketChannel.getRemoteAddress)
      print(": ")
      println(message)
    }
  }
}