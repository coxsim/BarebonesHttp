import java.io.{File, RandomAccessFile}

import scala.collection.mutable

/**
  * Created by simon on 28/11/2016.
  */
class ComplexRequestHandler extends RequestHandler {
  def handleStatic(uriStart: Int)(implicit httpRequest: HttpRequest, clientConnection: HttpClientConnection): Unit = {

    val sb = new mutable.StringBuilder(httpRequest.uriEnd - uriStart)
    for (i <- uriStart until httpRequest.uriEnd) {
      sb.append(httpRequest.buffer.get(i).toChar)
    }
    val path = sb.toString

    val file = new File("src/resources", path)
    if (!file.exists) {
      clientConnection.write("HTTP/1.1 404 Not Found\r\n")
      clientConnection.write(s"Content-Length: 0\r\n")
      clientConnection.write("\r\n")
    }
    else {
      val randomAccessFile = new RandomAccessFile(file, "r")
      val fileChannel = randomAccessFile.getChannel
      fileChannel.size()

      clientConnection.write("HTTP/1.1 200 OK\r\n")
      clientConnection.write(s"Content-Length: ${fileChannel.size()}\r\n")
      clientConnection.write("\r\n")
      //        clientConnection.write(fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size()))

      if (!clientConnection.transferFrom(fileChannel)) {
        // TODO: need some form of continuation
        println("Failed to write file!")
      }
    }
  }

  def handleDynamic(uriStart: Int)(implicit httpRequest: HttpRequest, clientConnection: HttpClientConnection): Unit = {
    val content = s"<html><body>Hello World! <pre>${httpRequest.uri}</pre></body></html>"

    clientConnection.write("HTTP/1.1 200 OK\r\n")
    clientConnection.write(s"Content-Length: ${content.length}\r\n")
    clientConnection.write("\r\n")
    clientConnection.write(content)
  }

  override def handleRequest(implicit httpRequest: HttpRequest, clientConnection: HttpClientConnection): Unit = {
    println("*** handling request ***")
    println(httpRequest.uri)

    def m(index: Int)(next: (Char, Int) => Unit): Unit = next(httpRequest.buffer.get(index).toChar, index)

    m(httpRequest.uriStart) { (char, index) => char match {
      case '/' => m(index + 1) { (char, index) => char match {
        case 's' => m(index + 1) { (char, index) => char match {
          case 't' => m(index + 1) { (char, index) => char match {
            case 'a' => m(index + 1) { (char, index) => char match {
              case 't' => m(index + 1) { (char, index) => char match {
                case 'i' => m(index + 1) { (char, index) => char match {
                  case 'c' => m(index + 1) { (char, index) => char match {
                    case '/' => m(index + 1) { (_, index) => handleStatic(index) }
                  } }
                } }
              } }
            } }
          } }
        } }
        case _ => handleDynamic(httpRequest.uriStart)
      } }
      case _ => handleDynamic(httpRequest.uriStart)
    } }
  }
}
