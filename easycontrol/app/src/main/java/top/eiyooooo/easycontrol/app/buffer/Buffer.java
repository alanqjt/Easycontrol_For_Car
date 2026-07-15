package top.eiyooooo.easycontrol.app.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;
/**
 * 类 Buffer
 * 说明：该类负责 Buffer 相关功能。
 */

public class Buffer {
  private volatile boolean isClosed = false;
  private volatile IOException closeCause;
  private final LinkedBlockingDeque<ByteBuffer> dataQueue = new LinkedBlockingDeque<>();

  public void write(ByteBuffer data) {
    if (isClosed) return;
    dataQueue.offerLast(data);
  }

  public void writeFirst(ByteBuffer data) {
    if (isClosed) return;
    dataQueue.offerFirst(data);
  }

  public synchronized ByteBuffer read(int len) throws InterruptedException, IOException {
    if (len < 0) throw new IOException("Invalid buffer read length: " + len);
    if (isClosed) throw closedException();
    ByteBuffer data = ByteBuffer.allocate(len);
    int bytesToRead = len;
    while (bytesToRead > 0) {
      ByteBuffer tmpData = dataQueue.takeFirst();
      if (isClosed) throw closedException();
      int remaining = tmpData.remaining();
      if (remaining <= bytesToRead) {
        data.put(tmpData);
        bytesToRead -= remaining;
      } else {
        int oldLimit = tmpData.limit();
        tmpData.limit(tmpData.position() + bytesToRead);
        data.put(tmpData);
        tmpData.limit(oldLimit);
        dataQueue.offerFirst(tmpData);
        bytesToRead = 0;
      }
    }
    data.flip();
    return data;
  }

  public synchronized ByteBuffer readNext() throws InterruptedException, IOException {
    if (isClosed) throw closedException();
    ByteBuffer byteBuffer = dataQueue.takeFirst();
    if (isClosed) throw closedException();
    return byteBuffer;
  }

  public ByteBuffer readByteArrayBeforeClose() {
    ByteBuffer byteBuffer = ByteBuffer.allocate(getSize());
    for (ByteBuffer tmpBuffer : dataQueue) byteBuffer.put(tmpBuffer);
    return byteBuffer;
  }

  public boolean isEmpty() {
    return dataQueue.isEmpty();
  }

  public int getSize() {
    int size = 0;
    for (ByteBuffer byteBuffer : dataQueue) size += byteBuffer.remaining();
    return size;
  }

  public void close() {
    close(null);
  }

  public void close(IOException cause) {
    if (isClosed) return;
    closeCause = cause;
    isClosed = true;
    dataQueue.offer(ByteBuffer.allocate(0));
  }

  private IOException closedException() {
    IOException cause = closeCause;
    if (cause == null) return new IOException("Buffer closed");
    return new IOException("Buffer closed: " + cause.getMessage(), cause);
  }

}
