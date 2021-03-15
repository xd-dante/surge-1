// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.internal.utils

import play.api.libs.json.{ Format, Json }

import scala.util.hashing.MurmurHash3

object BlockchainChecksum {
  def calculateChecksum[A](obj: Option[A], previousChecksumOpt: Option[String])(implicit format: Format[A]): String = {
    previousChecksumOpt.map { checksum =>
      calculateChecksum(obj, checksum)
    }.getOrElse(calculateNewChecksum(obj))
  }

  def calculateChecksum[A](obj: Option[A], previousChecksum: String)(implicit format: Format[A]): String = {
    val payload = Json.toJson(obj).toString + previousChecksum
    calculateChecksum(payload)
  }

  def calculateChecksum(payload: String): String = {
    val newHash = MurmurHash3.stringHash(payload)
    newHash.toString
  }

  private def calculateNewChecksum[A](obj: Option[A])(implicit format: Format[A]): String = {
    val payload = Json.toJson(obj).toString
    calculateChecksum(payload)
  }
}