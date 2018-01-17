package com.yubico.webauthn.rp

import java.security.MessageDigest
import java.security.KeyPair

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.yubico.scala.util.JavaConverters._
import com.yubico.u2f.crypto.BouncyCastleCrypto
import com.yubico.u2f.crypto.Crypto
import com.yubico.u2f.data.messages.key.util.U2fB64Encoding
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.FinishAssertionSteps
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.data.ArrayBuffer
import com.yubico.webauthn.data.AuthenticationExtensions
import com.yubico.webauthn.data.CollectedClientData
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.Base64UrlString
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import com.yubico.webauthn.data.impl.PublicKeyCredential
import com.yubico.webauthn.data.impl.AuthenticatorAssertionResponse
import com.yubico.webauthn.test.TestAuthenticator
import com.yubico.webauthn.util.BinaryUtil
import com.yubico.webauthn.util.WebAuthnCodecs
import org.junit.runner.RunWith
import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._
import scala.util.Failure
import scala.util.Success


@RunWith(classOf[JUnitRunner])
class RelyingPartyAssertionSpec extends FunSpec with Matchers {

  def jsonFactory: JsonNodeFactory = JsonNodeFactory.instance
  val crypto: Crypto = new BouncyCastleCrypto()

  object Defaults {

    val rpId = RelyingPartyIdentity(name = "Test party", id = "localhost")

    // These values were generated using TestAuthenticator.makeCredentialExample(TestAuthenticator.createCredential())
    val authenticatorData: ArrayBuffer = BinaryUtil.fromHex("49960de5880e8c687434170f6476605b8fe4aeb9a28632c7995cf3ba831d97630100000539").get
    val clientDataJson: String = """{"challenge":"AAEBAgMFCA0VIjdZEGl5Yls","origin":"localhost","hashAlgorithm":"SHA-256"}"""
    val credentialId: ArrayBuffer = BinaryUtil.fromHex("").get
    val credentialKey: KeyPair = new TestAuthenticator().importEcKeypair(
      privateBytes = BinaryUtil.fromHex("308193020100301306072a8648ce3d020106082a8648ce3d030107047930770201010420dd17580fe3c2e374c79fb30fbef657e326119d18ae538160c831851b92df19d7a00a06082a8648ce3d030107a144034200049613906235a63e87c085d52901bde35dcd9ca424526a4de551abe7ef4e3157aee6d01e1f4f805ee323ebf5ee7a54d4008d6bb97d9281a97f83e0be31dc3b8ef6").get,
      publicBytes = BinaryUtil.fromHex("3059301306072a8648ce3d020106082a8648ce3d030107034200049613906235a63e87c085d52901bde35dcd9ca424526a4de551abe7ef4e3157aee6d01e1f4f805ee323ebf5ee7a54d4008d6bb97d9281a97f83e0be31dc3b8ef6").get
    )
    val signature: ArrayBuffer = BinaryUtil.fromHex("3046022100e6f0c87a54aa16f2e0862035746f2732f3a8b27a404a29681f77d4a5c023861702210093b8b8da66ebce71c5bc467b61bc18277606ab895d25c226b066d5054345749a").get

    // These values are defined by the attestationObject and clientDataJson above
    val clientData = CollectedClientData(WebAuthnCodecs.json.readTree(clientDataJson))
    val clientDataJsonBytes: ArrayBuffer = clientDataJson.getBytes("UTF-8").toVector
    val challenge: ArrayBuffer = U2fB64Encoding.decode(clientData.challenge).toVector
    val requestedExtensions: Option[AuthenticationExtensions] = None
    val clientExtensionResults: AuthenticationExtensions = jsonFactory.objectNode()

  }

  def finishAssertion(
    allowCredentials: Option[Seq[PublicKeyCredentialDescriptor]] = Some(List(PublicKeyCredentialDescriptor(id = Defaults.credentialId))),
    authenticatorData: ArrayBuffer = Defaults.authenticatorData,
    callerTokenBindingId: Option[String] = None,
    challenge: ArrayBuffer = Defaults.challenge,
    clientDataJsonBytes: ArrayBuffer = Defaults.clientDataJsonBytes,
    clientExtensionResults: AuthenticationExtensions = Defaults.clientExtensionResults,
    credentialId: ArrayBuffer = Defaults.credentialId,
    credentialKey: KeyPair = Defaults.credentialKey,
    credentialRepository: Option[CredentialRepository] = None,
    origin: String = Defaults.rpId.id,
    requestedExtensions: Option[AuthenticationExtensions] = Defaults.requestedExtensions,
    rpId: RelyingPartyIdentity = Defaults.rpId,
    signature: ArrayBuffer = Defaults.signature
  ): FinishAssertionSteps = {

    val request = PublicKeyCredentialRequestOptions(
      rpId = Some(rpId.id).asJava,
      challenge = challenge,
      allowCredentials = Some(List(PublicKeyCredentialDescriptor(id = credentialId)).asJava).asJava,
      extensions = requestedExtensions.asJava
    )

    val response = PublicKeyCredential(
      credentialId,
      AuthenticatorAssertionResponse(
        clientDataJSON = clientDataJsonBytes,
        authenticatorData = authenticatorData,
        signature = signature
      ),
      clientExtensionResults
    )

    new RelyingParty(
      allowSelfAttestation = false,
      authenticatorRequirements = None.asJava,
      challengeGenerator = null,
      origins = List(origin).asJava,
      preferredPubkeyParams = Nil.asJava,
      rp = rpId,
      credentialRepository = credentialRepository getOrElse (
        new CredentialRepository {
          override def lookup(credId: Base64UrlString) = (if (credId == U2fB64Encoding.encode(credentialId.toArray)) Some(credentialKey.getPublic) else None).asJava
        }
      )
    )._finishAssertion(request, response, callerTokenBindingId.asJava)
  }

  describe("§7.2. Verifying an authentication assertion") {

    describe("When verifying a given PublicKeyCredential structure (credential) as part of an authentication ceremony, the Relying Party MUST proceed as follows:") {

      describe("1. Using credential’s id attribute (or the corresponding rawId, if base64url encoding is inappropriate for your use case), look up the corresponding credential public key.") {
        it("Fails if the credential ID is unknown.") {
          val steps = finishAssertion(credentialRepository = Some(new CredentialRepository { override def lookup(id: Base64UrlString) = None.asJava }))
          val step: steps.Step1 = steps.begin

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("Succeeds if the credential ID is known.") {
          val steps = finishAssertion(credentialRepository = Some(new CredentialRepository { override def lookup(id: Base64UrlString) = Some(Defaults.credentialKey.getPublic).asJava }))
          val step: steps.Step1 = steps.begin

          step.validations shouldBe a [Success[_]]
          step.pubkey should equal (Defaults.credentialKey.getPublic)
          step.next shouldBe a [Success[_]]
        }
      }

      describe("2. Let cData, aData and sig denote the value of credential’s response's clientDataJSON, authenticatorData, and signature respectively.") {
        it("Succeeds if all three are present.") {
          val steps = finishAssertion()
          val step: steps.Step2 = steps.begin.next.get

          step.validations shouldBe a [Success[_]]
          step.clientData should not be null
          step.authenticatorData should not be null
          step.signature should not be null
          step.next shouldBe a [Success[_]]
        }

        it("Fails if clientDataJSON is missing.") {
          val steps = finishAssertion(clientDataJsonBytes = null)
          val step: steps.Step2 = steps.begin.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("Fails if authenticatorData is missing.") {
          val steps = finishAssertion(authenticatorData = null)
          val step: steps.Step2 = steps.begin.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("Fails if signature is missing.") {
          val steps = finishAssertion(signature = null)
          val step: steps.Step2 = steps.begin.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }
      }

      describe("3. Perform JSON deserialization on cData to extract the client data C used for the signature.") {
        it("Fails if cData is not valid JSON.") {
          val malformedClientData = Vector[Byte]('{'.toByte)
          val steps = finishAssertion(clientDataJsonBytes = malformedClientData)
          val step: steps.Step3 = steps.begin.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe a [JsonParseException]
          step.next shouldBe a [Failure[_]]
        }

        it("Succeeds if cData is valid JSON.") {
          val malformedClientData = "{}".getBytes("UTF-8").toVector
          val steps = finishAssertion(clientDataJsonBytes = malformedClientData)
          val step: steps.Step3 = steps.begin.next.get.next.get

          step.validations shouldBe a [Success[_]]
          step.clientData should not be null
          step.next shouldBe a [Success[_]]
        }
      }

      it("4. Verify that the type in C is the string webauthn.get.") {
        fail("Not implemented.")
      }

      it("5. Verify that the challenge member of C matches the challenge that was sent to the authenticator in the PublicKeyCredentialRequestOptions passed to the get() call.") {
        val steps = finishAssertion(challenge = Vector.fill(16)(0: Byte))
        val step: steps.Step4 = steps.begin.next.get.next.get.next.get

        step.validations shouldBe a [Failure[_]]
        step.validations.failed.get shouldBe an [AssertionError]
        step.next shouldBe a [Failure[_]]
      }

      it("6. Verify that the origin member of C matches the Relying Party's origin.") {
        val steps = finishAssertion(origin = "root.evil")
        val step: steps.Step5 = steps.begin.next.get.next.get.next.get.next.get

        step.validations shouldBe a [Failure[_]]
        step.validations.failed.get shouldBe an [AssertionError]
        step.next shouldBe a [Failure[_]]
      }

      describe("7. Verify that the tokenBindingId member of C (if present) matches the Token Binding ID for the TLS connection over which the signature was obtained.") {
        it("Verification succeeds if neither side specifies token binding ID.") {
          val steps = finishAssertion()
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Success[_]]
          step.next shouldBe a [Success[_]]
        }

        it("Verification succeeds if both sides specify the same token binding ID.") {
          val clientDataJsonBytes: ArrayBuffer = BinaryUtil.fromHex("7b226368616c6c656e6765223a224141454241674d4643413056496a645a45476c35596c73222c226f726967696e223a226c6f63616c686f7374222c2268617368416c676f726974686d223a225348412d323536222c22746f6b656e42696e64696e674964223a2259454c4c4f575355424d4152494e45227d").get

          val steps = finishAssertion(
            callerTokenBindingId = Some("YELLOWSUBMARINE"),
            clientDataJsonBytes = clientDataJsonBytes
          )
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Success[_]]
          step.next shouldBe a [Success[_]]
        }

        it("Verification fails if caller specifies token binding ID but assertion does not.") {
          val steps = finishAssertion(callerTokenBindingId = Some("YELLOWSUBMARINE"))
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("Verification fails if assertion specifies token binding ID but caller does not.") {
          val clientDataJsonBytes: ArrayBuffer = BinaryUtil.fromHex("7b226368616c6c656e6765223a224141454241674d4643413056496a645a45476c35596c73222c226f726967696e223a226c6f63616c686f7374222c2268617368416c676f726974686d223a225348412d323536222c22746f6b656e42696e64696e674964223a2259454c4c4f575355424d4152494e45227d").get

          val steps = finishAssertion(
            callerTokenBindingId = None,
            clientDataJsonBytes = clientDataJsonBytes
          )
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("Verification fails if assertion and caller specify different token binding IDs.") {
          val clientDataJsonBytes: ArrayBuffer = BinaryUtil.fromHex("7b226368616c6c656e6765223a224141454241674d4643413056496a645a45476c35596c73222c226f726967696e223a226c6f63616c686f7374222c2268617368416c676f726974686d223a225348412d323536222c22746f6b656e42696e64696e674964223a2259454c4c4f575355424d4152494e45227d").get

          val steps = finishAssertion(
            callerTokenBindingId = Some("ORANGESUBMARINE"),
            clientDataJsonBytes = clientDataJsonBytes
          )
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }
      }

      describe("8. Verify that the") {
        it("clientExtensions member of C is a subset of the extensions requested by the Relying Party.") {
          val failSteps = finishAssertion(
            clientDataJsonBytes =
              WebAuthnCodecs.json.writeValueAsBytes(
                WebAuthnCodecs.json.readTree(Defaults.clientDataJson).asInstanceOf[ObjectNode]
                  .set("clientExtensions", jsonFactory.objectNode().set("foo", jsonFactory.textNode("boo")))
              ).toVector
          )
          val failStep: failSteps.Step7 = failSteps.begin.next.get.next.get.next.get.next.get.next.get.next.get

          failStep.validations shouldBe a [Failure[_]]
          failStep.validations.failed.get shouldBe an[AssertionError]
          failStep.next shouldBe a [Failure[_]]

          val successSteps = finishAssertion(
            requestedExtensions = Some(jsonFactory.objectNode().set("foo", jsonFactory.textNode("bar"))),
            clientDataJsonBytes =
              WebAuthnCodecs.json.writeValueAsBytes(
                WebAuthnCodecs.json.readTree(Defaults.clientDataJson).asInstanceOf[ObjectNode]
                  .set("clientExtensions", jsonFactory.objectNode().set("foo", jsonFactory.textNode("boo")))
              ).toVector
          )
          val successStep: successSteps.Step7 = successSteps.begin.next.get.next.get.next.get.next.get.next.get.next.get

          successStep.validations shouldBe a [Success[_]]
          successStep.next shouldBe a [Success[_]]
        }

        it("authenticatorExtensions in C is also a subset of the extensions requested by the Relying Party.") {
          val failSteps = finishAssertion(
            clientDataJsonBytes =
              WebAuthnCodecs.json.writeValueAsBytes(
                WebAuthnCodecs.json.readTree(Defaults.clientDataJson).asInstanceOf[ObjectNode]
                  .set("authenticatorExtensions", jsonFactory.objectNode().set("foo", jsonFactory.textNode("boo")))
              ).toVector
          )
          val failStep: failSteps.Step7 = failSteps.begin.next.get.next.get.next.get.next.get.next.get.next.get

          failStep.validations shouldBe a [Failure[_]]
          failStep.validations.failed.get shouldBe an[AssertionError]
          failStep.next shouldBe a [Failure[_]]

          val successSteps = finishAssertion(
            requestedExtensions = Some(jsonFactory.objectNode().set("foo", jsonFactory.textNode("bar"))),
            clientDataJsonBytes =
              WebAuthnCodecs.json.writeValueAsBytes(
                WebAuthnCodecs.json.readTree(Defaults.clientDataJson).asInstanceOf[ObjectNode]
                  .set("authenticatorExtensions", jsonFactory.objectNode().set("foo", jsonFactory.textNode("boo")))
              ).toVector
          )
          val successStep: successSteps.Step7 = successSteps.begin.next.get.next.get.next.get.next.get.next.get.next.get

          successStep.validations shouldBe a [Success[_]]
          successStep.next shouldBe a [Success[_]]
        }
      }

      describe("9. Verify that the rpIdHash in aData is the SHA-256 hash of the RP ID expected by the Relying Party.") {
        it("Fails if RP ID is different.") {
          val steps = finishAssertion(rpId = Defaults.rpId.copy(id = "root.evil"))
          val step: steps.Step8 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("Succeeds if RP ID is the same.") {
          val steps = finishAssertion()
          val step: steps.Step8 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Success[_]]
          step.next shouldBe a [Success[_]]
        }
      }

      describe("10. Let hash be the result of computing a hash over the cData using the algorithm represented by the hashAlgorithm member of C.") {
        it("SHA-256 is allowed.") {
          val steps = finishAssertion()
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Success[_]]
          step.next shouldBe a [Success[_]]
          step.clientDataJsonHash should equal (MessageDigest.getInstance("SHA-256").digest(Defaults.clientDataJsonBytes.toArray).toVector)
        }

        def checkForbidden(algorithm: String): Unit = {
          it(s"${algorithm} is forbidden.") {
            val steps = finishAssertion(
              clientDataJsonBytes =
                WebAuthnCodecs.json.writeValueAsBytes(
                  WebAuthnCodecs.json.readTree(Defaults.clientDataJson).asInstanceOf[ObjectNode]
                    .set("hashAlgorithm", jsonFactory.textNode(algorithm))
                ).toVector
            )
            val step: steps.Step9 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get

            step.validations shouldBe a [Failure[_]]
            step.validations.failed.get shouldBe an [AssertionError]
            step.next shouldBe a [Failure[_]]
          }
        }
        checkForbidden("MD5")
        checkForbidden("SHA1")
      }

      describe("11. Using the credential public key looked up in step 1, verify that sig is a valid signature over the binary concatenation of aData and hash.") {
        it("The default test case succeeds.") {
          val steps = finishAssertion()
          val step: steps.Step6 = steps.begin.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Success[_]]
          step.next shouldBe a [Success[_]]
          step.clientDataJsonHash should equal (MessageDigest.getInstance("SHA-256").digest(Defaults.clientDataJsonBytes.toArray).toVector)
        }

        it("A mutated clientDataJSON fails verification.") {
          val steps = finishAssertion(
            clientDataJsonBytes = WebAuthnCodecs.json.writeValueAsBytes(
              WebAuthnCodecs.json.readTree(Defaults.clientDataJson).asInstanceOf[ObjectNode]
                .set("foo", jsonFactory.textNode("bar"))
            ).toVector
          )
          val step: steps.Step10 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("A test case with a different signed RP ID hash fails.") {
          val rpId = "ARGHABLARGHLER"
          val rpIdHash: ArrayBuffer = crypto.hash(rpId).toVector
          val steps = finishAssertion(
            authenticatorData = rpIdHash ++ Defaults.authenticatorData.drop(32),
            rpId = Defaults.rpId.copy(id = rpId)
          )
          val step: steps.Step10 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("A test case with a different signed flags field fails.") {
          val steps = finishAssertion(
            authenticatorData = Defaults.authenticatorData.updated(32, (Defaults.authenticatorData(32) | 0x02).toByte)
          )
          val step: steps.Step10 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }

        it("A test case with a different signed signature counter fails.") {
          val steps = finishAssertion(
            authenticatorData = Defaults.authenticatorData.updated(33, 42.toByte)
          )
          val step: steps.Step10 = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get

          step.validations shouldBe a [Failure[_]]
          step.validations.failed.get shouldBe an [AssertionError]
          step.next shouldBe a [Failure[_]]
        }
      }

      describe("12. If the signature counter value adata.signCount is nonzero or the value stored in conjunction with credential’s id attribute is nonzero, then run the following substep:") {
        describe("If the signature counter value adata.signCount is") {
          describe("greater than the signature counter value stored in conjunction with credential’s id attribute.") {
            it("Update the stored signature counter value, associated with credential’s id attribute, to be the value of adata.signCount.") {
              fail("Not implemented.")
            }
          }
          describe("less than or equal to the signature counter value stored in conjunction with credential’s id attribute.") {
            it("This is an signal that the authenticator may be cloned, i.e. at least two copies of the credential private key may exist and are being used in parallel. Relying Parties should incorporate this information into their risk scoring. Whether the Relying Party updates the stored signature counter value in this case, or not, or fails the authentication ceremony or not, is Relying Party-specific.") {
              fail("Not implemented.")
            }
          }
        }
      }

      it("13. If all the above steps are successful, continue with the authentication ceremony as appropriate. Otherwise, fail the authentication ceremony.") {
        val steps = finishAssertion()
        val step: steps.Finished = steps.begin.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get.next.get

        step.validations shouldBe a [Success[_]]
        steps.run shouldBe a [Success[_]]
        step.success should be (true)
        steps.run.get should be (true)
      }

    }

  }

}
