package com.yubico.webauthn

import java.util.Optional
import java.util.function.Supplier

import com.yubico.scala.util.JavaConverters._
import com.yubico.u2f.attestation.MetadataService
import com.yubico.u2f.crypto.ChallengeGenerator
import com.yubico.u2f.crypto.Crypto
import com.yubico.u2f.crypto.BouncyCastleCrypto
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.PublicKeyCredentialParameters
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.AuthenticationExtensionsClientInputs
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.AuthenticatorAttestationResponse
import com.yubico.webauthn.data.Base64UrlString
import com.yubico.webauthn.data.AuthenticatorAssertionResponse
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import com.yubico.webauthn.data.AttestationConveyancePreference
import com.yubico.webauthn.data.RegistrationResult
import com.yubico.webauthn.data.AssertionResult

import scala.util.Try


class RelyingParty (
  val rp: RelyingPartyIdentity,
  val challengeGenerator: ChallengeGenerator,
  val preferredPubkeyParams: java.util.List[PublicKeyCredentialParameters],
  val origins: java.util.List[String],
  val authenticatorRequirements: Optional[AuthenticatorSelectionCriteria] = None.asJava,
  val attestationConveyancePreference: Optional[AttestationConveyancePreference] = None.asJava,
  val crypto: Crypto = new BouncyCastleCrypto,
  val allowMissingTokenBinding: Boolean = false,
  val allowUntrustedAttestation: Boolean = false,
  val credentialRepository: CredentialRepository,
  val metadataService: Optional[MetadataService] = None.asJava,
  val validateSignatureCounter: Boolean = true,
  val validateTypeAttribute: Boolean = true
) {

  def startRegistration(
    user: UserIdentity,
    excludeCredentials: Optional[java.util.Collection[PublicKeyCredentialDescriptor]] = None.asJava,
    extensions: Optional[AuthenticationExtensionsClientInputs] = None.asJava
  ): PublicKeyCredentialCreationOptions =
    PublicKeyCredentialCreationOptions(
      rp = rp,
      user = user,
      challenge = challengeGenerator.generateChallenge().toVector,
      pubKeyCredParams = preferredPubkeyParams,
      excludeCredentials = excludeCredentials,
      authenticatorSelection = authenticatorRequirements,
      attestation = attestationConveyancePreference.asScala getOrElse AttestationConveyancePreference.default,
      extensions = extensions
    )

  def finishRegistration(
    request: PublicKeyCredentialCreationOptions,
    response: PublicKeyCredential[AuthenticatorAttestationResponse],
    callerTokenBindingId: Optional[Base64UrlString] = None.asJava
  ): Try[RegistrationResult] =
    _finishRegistration(request, response, callerTokenBindingId).run

  private[webauthn] def _finishRegistration(
    request: PublicKeyCredentialCreationOptions,
    response: PublicKeyCredential[AuthenticatorAttestationResponse],
    callerTokenBindingId: Optional[Base64UrlString] = None.asJava
  ): FinishRegistrationSteps =
    FinishRegistrationSteps(
      request = request,
      response = response,
      callerTokenBindingId = callerTokenBindingId,
      credentialRepository = credentialRepository,
      origins = origins,
      rpId = rp.id,
      crypto = crypto,
      allowMissingTokenBinding = allowMissingTokenBinding,
      allowUntrustedAttestation = allowUntrustedAttestation,
      metadataService = metadataService,
      validateTypeAttribute = validateTypeAttribute
    )

  def startAssertion(
    allowCredentials: Optional[java.util.List[PublicKeyCredentialDescriptor]] = None.asJava,
    extensions: Optional[AuthenticationExtensionsClientInputs] = None.asJava
  ): PublicKeyCredentialRequestOptions =
    PublicKeyCredentialRequestOptions(
      rpId = Some(rp.id).asJava,
      challenge = challengeGenerator.generateChallenge().toVector,
      allowCredentials = allowCredentials,
      extensions = extensions
    )

  def finishAssertion(
    request: PublicKeyCredentialRequestOptions,
    response: PublicKeyCredential[AuthenticatorAssertionResponse],
    getUserHandle: Supplier[Base64UrlString],
    callerTokenBindingId: Optional[Base64UrlString] = None.asJava
  ): Try[AssertionResult] =
    _finishAssertion(request, response, getUserHandle, callerTokenBindingId).run

  private[webauthn] def _finishAssertion(
    request: PublicKeyCredentialRequestOptions,
    response: PublicKeyCredential[AuthenticatorAssertionResponse],
    getUserHandle: Supplier[Base64UrlString],
    callerTokenBindingId: Optional[Base64UrlString] = None.asJava
  ): FinishAssertionSteps =
    FinishAssertionSteps(
      request = request,
      response = response,
      callerTokenBindingId = callerTokenBindingId,
      origins = origins,
      rpId = rp.id,
      crypto = crypto,
      credentialRepository = credentialRepository,
      getUserHandle = getUserHandle,
      allowMissingTokenBinding = allowMissingTokenBinding,
      validateSignatureCounter = validateSignatureCounter,
      validateTypeAttribute = validateTypeAttribute
    )

}
