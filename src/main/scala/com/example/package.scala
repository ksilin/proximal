package com

import java.io.InputStream

package object example {

  case class Message(key: String, value: String, partition: Option[Int] = None)
  case class Records(records: List[Message])

  case class Offset(partition: Int,offset: Int,error_code: Option[Int],error: Option[String])
  case class ProduceResponse(offsets : List[Offset], key_schema_id: Option[Int], value_schema_id: Option[Int])

}
