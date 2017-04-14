package at

import scala.language.reflectiveCalls

package object hazm {
  def using[R <: {def close():Unit},U](r:R)(f:(R)=>U):U = try { f(r) } finally { r.close() }
  def on[T](t:T)(f:(T)=>Unit):T = { f(t); t }
}
