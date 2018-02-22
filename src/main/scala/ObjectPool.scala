import java.util

/**
  * Created by simon on 28/11/2016.
  */
class ObjectPool[T](capacity: Int, factory: => T) {
  class PooledObject(val obj: T) {
    def release(): Unit = ObjectPool.this.release(PooledObject.this)
  }

  private val pool: util.Queue[PooledObject] = new util.ArrayDeque[PooledObject](capacity)
  for (_ <- 0 until capacity) pool.add(new PooledObject(factory))


  def acquire(): PooledObject = {
    val next = pool.poll()
    if (next == null) new PooledObject(factory)
    else next
  }

  private def release(item: PooledObject) = pool.add(item)
}
