package demo.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yubico.u2f.attestation.MetadataService;
import com.yubico.u2f.crypto.BouncyCastleCrypto;
import com.yubico.u2f.crypto.ChallengeGenerator;
import com.yubico.u2f.crypto.RandomChallengeGenerator;
import com.yubico.u2f.data.messages.key.util.U2fB64Encoding;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AssertionResult;
import com.yubico.webauthn.data.Direct$;
import com.yubico.webauthn.data.PublicKey$;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RegistrationResult;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.util.BinaryUtil;
import demo.webauthn.data.AssertionRequest;
import demo.webauthn.data.AssertionResponse;
import demo.webauthn.data.CredentialRegistration;
import demo.webauthn.data.RegistrationRequest;
import demo.webauthn.data.RegistrationResponse;
import demo.webauthn.json.ScalaJackson;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;
import scala.util.Try;

public class WebAuthnServer {
    private static final Logger logger = LoggerFactory.getLogger(WebAuthnServer.class);

    private final Cache<String, AssertionRequest> assertRequestStorage = newCache();
    private final Cache<String, RegistrationRequest> registerRequestStorage = newCache();
    private final RegistrationStorage userStorage = new InMemoryRegistrationStorage();
    private final Cache<AssertionRequest, AuthenticatedAction> authenticatedActions = newCache();

    private final ChallengeGenerator challengeGenerator = new RandomChallengeGenerator();

    private final MetadataService metadataService = new MetadataService();

    private final Clock clock = Clock.systemDefaultZone();
    private final ObjectMapper jsonMapper = new ScalaJackson().get();


    private final RelyingParty rp = new RelyingParty(
        Config.getRpIdentity(),
        challengeGenerator,
        Collections.singletonList(new PublicKeyCredentialParameters(-7L, PublicKey$.MODULE$)),
        Config.getOrigins(),
        Optional.empty(),
        Optional.of(Direct$.MODULE$),
        new BouncyCastleCrypto(),
        true,
        true,
        userStorage,
        Optional.of(metadataService),
        true,
        false
    );

    private static <K, V> Cache<K, V> newCache() {
        return CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
    }

    public Either<String, RegistrationRequest> startRegistration(String username, String displayName, String credentialNickname) {
        logger.trace("startRegistration username: {}, credentialNickname: {}", username, credentialNickname);

        if (userStorage.getRegistrationsByUsername(username).isEmpty()) {
            byte[] userId = challengeGenerator.generateChallenge();

            RegistrationRequest request = new RegistrationRequest(
                username,
                credentialNickname,
                U2fB64Encoding.encode(challengeGenerator.generateChallenge()),
                rp.startRegistration(
                    new UserIdentity(username, displayName, userId, Optional.empty()),
                    Optional.of(userStorage.getCredentialIdsForUsername(username)),
                    Optional.empty()
                )
            );
            registerRequestStorage.put(request.getRequestId(), request);
            return new Right(request);
        } else {
            return new Left("The username \"" + username + "\" is already registered.");
        }
    }

    public <T> Either<String, AssertionRequest> startAddCredential(String username, String credentialNickname, Function<RegistrationRequest, Either<List<String>, T>> whenAuthenticated) {
        logger.trace("startAddCredential username: {}, credentialNickname: {}", username, credentialNickname);

        if (username == null || username.isEmpty()) {
            return Left.apply("username must not be empty.");
        }

        Collection<CredentialRegistration> registrations = userStorage.getRegistrationsByUsername(username);

        if (registrations.isEmpty()) {
            return new Left("The username \"" + username + "\" is not registered.");
        } else {
            final UserIdentity existingUser = registrations.stream().findAny().get().getUserIdentity();

            AuthenticatedAction<T> action = (SuccessfulAuthenticationResult result) -> {
                RegistrationRequest request = new RegistrationRequest(
                    username,
                    credentialNickname,
                    U2fB64Encoding.encode(challengeGenerator.generateChallenge()),
                    rp.startRegistration(
                        existingUser,
                        Optional.of(userStorage.getCredentialIdsForUsername(username)),
                        Optional.empty()
                    )
                );
                registerRequestStorage.put(request.getRequestId(), request);

                return whenAuthenticated.apply(request);
            };

            return Right.apply(startAuthenticatedAction(Optional.of(username), action));
        }
    }

    @Value
    public static class SuccessfulRegistrationResult {
        final boolean success = true;
        RegistrationRequest request;
        RegistrationResponse response;
        CredentialRegistration registration;
        boolean attestationTrusted;
    }

    public Either<List<String>, SuccessfulRegistrationResult> finishRegistration(String responseJson) {
        logger.trace("finishRegistration responseJson: {}", responseJson);
        RegistrationResponse response = null;
        try {
            response = jsonMapper.readValue(responseJson, RegistrationResponse.class);
        } catch (IOException e) {
            logger.error("JSON error in finishRegistration; responseJson: {}", responseJson, e);
            return Left.apply(Arrays.asList("Registration failed!", "Failed to decode response object.", e.getMessage()));
        }

        RegistrationRequest request = registerRequestStorage.getIfPresent(response.getRequestId());
        registerRequestStorage.invalidate(response.getRequestId());

        if (request == null) {
            logger.debug("fail finishRegistration responseJson: {}", responseJson);
            return Left.apply(Arrays.asList("Registration failed!", "No such registration in progress."));
        } else {
            Try<RegistrationResult> registrationTry = rp.finishRegistration(
                request.getPublicKeyCredentialCreationOptions(),
                response.getCredential(),
                Optional.empty()
            );

            if (registrationTry.isSuccess()) {
                return Right.apply(
                    new SuccessfulRegistrationResult(
                        request,
                        response,
                        addRegistration(
                            request.getUsername(),
                            request.getPublicKeyCredentialCreationOptions().user(),
                            request.getCredentialNickname(),
                            response,
                            registrationTry.get()
                        ),
                        registrationTry.get().attestationTrusted()
                    )
                );
            } else {
                logger.debug("fail finishRegistration responseJson: {}", responseJson, registrationTry.failed().get());
                return Left.apply(Arrays.asList("Registration failed!", registrationTry.failed().get().getMessage()));
            }

        }
    }

    public AssertionRequest startAuthentication(Optional<String> username) {
        logger.trace("startAuthentication username: {}", username);
        AssertionRequest request = new AssertionRequest(
            username,
            U2fB64Encoding.encode(challengeGenerator.generateChallenge()),
            rp.startAssertion(
                username.map(userStorage::getCredentialIdsForUsername),
                Optional.empty()
            )
        );

        assertRequestStorage.put(request.getRequestId(), request);

        return request;
    }

    @Value
    public static class SuccessfulAuthenticationResult {
        final boolean success = true;
        AssertionRequest request;
        AssertionResponse response;
        Collection<CredentialRegistration> registrations;
    }

    public Either<List<String>, SuccessfulAuthenticationResult> finishAuthentication(String responseJson) {
        logger.trace("finishAuthentication responseJson: {}", responseJson);

        final AssertionResponse response;
        try {
            response = jsonMapper.readValue(responseJson, AssertionResponse.class);
        } catch (IOException e) {
            logger.debug("Failed to decode response object", e);
            return Left.apply(Arrays.asList("Assertion failed!", "Failed to decode response object.", e.getMessage()));
        }

        AssertionRequest request = assertRequestStorage.getIfPresent(response.getRequestId());
        assertRequestStorage.invalidate(response.getRequestId());

        if (request == null) {
            return Left.apply(Arrays.asList("Assertion failed!", "No such assertion in progress."));
        } else {
            Optional<String> returnedUserHandle = Optional.ofNullable(response.getCredential().response().userHandleBase64());

            final String username;
            if (request.getUsername().isPresent()) {
                username = request.getUsername().get();
            } else {
                username = userStorage.getUsername(returnedUserHandle.get()).orElse(null);
            }

            final String userHandle = returnedUserHandle.orElseGet(() ->
                username == null
                    ? null
                    : userStorage.getUserHandle(username)
                        .map(BinaryUtil::toBase64)
                        .orElse(null)
            );

            if (username == null || username.isEmpty() || userHandle == null || userHandle.isEmpty()) {
                return Left.apply(Arrays.asList("User handle must be returned if username was not supplied in startAuthentication"));
            } else {
                Try<AssertionResult> assertionTry = rp.finishAssertion(
                    request.getPublicKeyCredentialRequestOptions(),
                    response.getCredential(),
                    () -> userHandle,
                    Optional.empty()
                );

                if (assertionTry.isSuccess()) {
                    final AssertionResult result = assertionTry.get();

                    if (result.success()) {
                        try {
                            userStorage.updateSignatureCountForUsername(
                                username,
                                response.getCredential().id(),
                                result.signatureCount()
                            );
                        } catch (Exception e) {
                            logger.error(
                                "Failed to update signature count for user \"{}\", credential \"{}\"",
                                username,
                                response.getCredential().id(),
                                e
                            );
                        }

                        return Right.apply(
                            new SuccessfulAuthenticationResult(
                                request,
                                response,
                                userStorage.getRegistrationsByUsername(username)
                            )
                        );
                    } else {
                        return Left.apply(Arrays.asList("Assertion failed: Invalid assertion."));
                    }

                } else {
                    logger.debug("Assertion failed", assertionTry.failed().get());
                    return Left.apply(Arrays.asList("Assertion failed!", assertionTry.failed().get().getMessage()));
                }
            }
        }
    }

    public AssertionRequest startAuthenticatedAction(Optional<String> username, AuthenticatedAction<?> action) {
        final AssertionRequest request = startAuthentication(username);
        synchronized (authenticatedActions) {
            authenticatedActions.put(request, action);
        }
        return request;
    }

    public Either<List<String>, ?> finishAuthenticatedAction(String responseJson) {
        return com.yubico.util.Either.fromScala(finishAuthentication(responseJson))
            .flatMap(result -> {
                AuthenticatedAction<?> action = authenticatedActions.getIfPresent(result.request);
                authenticatedActions.invalidate(result.request);
                if (action == null) {
                    return com.yubico.util.Either.left(Collections.singletonList(
                        "No action was associated with assertion request ID: " + result.getRequest().getRequestId()
                    ));
                } else {
                    return com.yubico.util.Either.fromScala(action.apply(result));
                }
            })
            .toScala();
    }

    public <T> Either<List<String>, AssertionRequest> deregisterCredential(String username, String credentialId, Function<CredentialRegistration, T> resultMapper) {
        logger.trace("deregisterCredential username: {}, credentialId: {}", username, credentialId);

        if (username == null || username.isEmpty()) {
            return Left.apply(Arrays.asList("Username must not be empty."));
        }

        if (credentialId == null || credentialId.isEmpty()) {
            return Left.apply(Arrays.asList("Credential ID must not be empty."));
        }

        AuthenticatedAction<T> action = (SuccessfulAuthenticationResult result) -> {
            Optional<CredentialRegistration> credReg = userStorage.getRegistrationByUsernameAndCredentialId(username, credentialId);

            if (credReg.isPresent()) {
                userStorage.removeRegistrationByUsername(username, credReg.get());
                return Right.apply(resultMapper.apply(credReg.get()));
            } else {
                return Left.apply(Arrays.asList("Credential ID not registered:" + credentialId));
            }
        };

        return Right.apply(startAuthenticatedAction(Optional.of(username), action));
    }

    private CredentialRegistration addRegistration(String username, UserIdentity userIdentity, String nickname, RegistrationResponse response, RegistrationResult registration) {
        CredentialRegistration reg = CredentialRegistration.builder()
            .username(username)
            .userIdentity(userIdentity)
            .credentialNickname(nickname)
            .registrationTime(clock.instant())
            .registration(registration)
            .signatureCount(response.getCredential().response().attestation().authenticatorData().signatureCounter())
            .build();

        logger.debug(
            "Adding registration: username: {}, nickname: {}, registration: {}, credentialId: {}, public key cose: {}",
            username,
            nickname,
            registration,
            registration.keyId().idBase64(),
            registration.publicKeyCose()
        );
        userStorage.addRegistrationByUsername(username, reg);
        return reg;
    }
}
