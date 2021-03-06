package com.yubico.webauthn

import java.net.URL
import java.time.LocalDate

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize


private class LocalDateJsonSerializer extends JsonSerializer[LocalDate] {
  override def serialize(t: LocalDate, jsonGenerator: JsonGenerator, serializerProvider: SerializerProvider): Unit = {
    jsonGenerator.writeString(t.toString)
  }
}

/**
  * Reference to a particular version of a specification document.
  *
  * @param url Address to this version of the specification.
  * @param latestVersionUrl Address to the latest version of this specification.
  * @param status An object indicating the status of the specification document.
  * @param releaseDate The release date of the specification document.
  */
case class Specification(
  url: URL,
  latestVersionUrl: URL,
  status: DocumentStatus,
  @JsonSerialize(using = classOf[LocalDateJsonSerializer])
  releaseDate: LocalDate
)

/**
  * Contains version information for the com.yubico.webauthn package.
  *
  * @see [[Specification]]
  */
object VersionInfo {

  /**
    * Represents the specification this implementation is based on
    */
  val specification = Specification(
    url = new URL("https://www.w3.org/TR/2018/CR-webauthn-20180320/"),
    latestVersionUrl = new URL("https://www.w3.org/TR/webauthn/"),
    status = DocumentStatus.CANDIDATE_RECOMMENDATION,
    releaseDate = LocalDate.parse("2018-03-20")
  )

}
