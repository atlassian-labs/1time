package io.atlassian.authentication.onetime.io.atlassian.authentication.onetime.service

import io.atlassian.authentication.onetime.arbEmailAddress
import io.atlassian.authentication.onetime.arbHMACDigest
import io.atlassian.authentication.onetime.arbInstant
import io.atlassian.authentication.onetime.arbIssuer
import io.atlassian.authentication.onetime.arbOtpLength
import io.atlassian.authentication.onetime.arbTotpSecret
import io.atlassian.authentication.onetime.core.CustomHOTPGenerator
import io.atlassian.authentication.onetime.core.CustomTOTPGenerator
import io.atlassian.authentication.onetime.core.HMACDigest
import io.atlassian.authentication.onetime.core.OTPLength
import io.atlassian.authentication.onetime.core.TOTP
import io.atlassian.authentication.onetime.model.Issuer
import io.atlassian.authentication.onetime.model.TOTPSecret
import io.atlassian.authentication.onetime.service.DefaultTOTPService
import io.atlassian.authentication.onetime.service.TOTPConfiguration
import io.atlassian.authentication.onetime.service.TOTPVerificationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DefaultTOTPServiceTest : FunSpec({

  test("generateTotpSecret") { }

  context("Generate TOTP URI") {
    test("should generate URI correctly when issuer is present") {
      checkAll(
        arbTotpSecret,
        arbEmailAddress,
        arbIssuer,
        arbOtpLength,
        arbHMACDigest,
        Arb.int(1..60)
      ) { secret, email, issuer, otpLength, digest, timeStep ->
        given(
          TestState(timeStep = timeStep, otpLength = otpLength, digest = digest)
        ) {
          val uri = defaultTOTPService.generateTOTPUrl(
            secret,
            email,
            issuer
          )

          uri.scheme shouldBe "otpauth"
          uri.authority shouldBe "totp"
          uri.path shouldBe "/${issuer.value}:${email.value}"
          uri.queryParams() shouldContainInOrder listOf(
            QueryParam("secret", secret.base32Encoded),
            QueryParam("issuer", issuer.value),
            QueryParam("algorithm", digest.toQueryParam()),
            QueryParam("digits", otpLength.value.toString()),
            QueryParam("period", timeStep.toString()),
          )
        }
      }
    }
  }

  context("verify totp") {

    /**
     * In this test we position the server clock close to the end of the time-step. This is the upper bound.
     * We then calculate the beginning of the oldest time step that is allowed. This is the lower bound
     * We then calculate a random delay in the range of the lowest bound to upper bound inclusive.
     * This can give us delays up to 59 seconds when we allow a past time step.
     *
     * Example:
     *                          _____
     *                           T+1
     *                    ______<- upper bound
     *                      T
     *  lower bound->_____
     *                T-1
     */
    test("should validate TOTP with delay") {
      checkAll(
        arbInstant,
        arbOtpLength,
        arbTotpSecret,
        Arb.int(30..90), // time step
        Arb.int(0..3)    // past steps
      ) { time, otpLength, secret, timeStep, allowedPastSteps ->

        val serverTimeStep = obtainCurrentTimeStep(Clock.fixed(time, ZoneOffset.UTC), timeStep)
        val serverTimeInSeconds = (serverTimeStep * timeStep) + timeStep - 1
        val clientClockLowerBound = serverTimeInSeconds - ((timeStep * (allowedPastSteps + 1)) - 1)

        checkAll(Arb.long(clientClockLowerBound..serverTimeInSeconds)) { clientTime ->
          given(
            TestState(
              clock = Clock.fixed(Instant.ofEpochSecond(serverTimeInSeconds), ZoneOffset.UTC),
              timeStep = timeStep,
              otpLength = otpLength,
              allowedPastTimeSteps = allowedPastSteps
            )
          ) {
            val delayedClock = Clock.fixed(Instant.ofEpochSecond(clientTime), ZoneOffset.UTC)
            val userInputTotp = localTOTPGeneration(secret, delayedClock, otpLength, timeStep)
            val verificationResult = defaultTOTPService.verify(
              userInputTotp,
              secret,
              allowedPastSteps = allowedPastSteps
            )
            verificationResult should beInstanceOf<TOTPVerificationResult.Success>()
          }
        }
      }
    }

    /**
     * In this test we position the server clock at the very beginning of the time step. This is the lower bound.
     * We then calculate the end of the time step further in the future that is allowed. This is the upper bound.
     * We then calculate a random delay in the range of the lowest bound to upper bound inclusive.
     *
     * Example:
     *                    _____<- upper bound
     *                     T+1
     * lower bound->______
     *                T
     *         _____
     *          T-1
     */
    test("should validate TOTP with future drift") {
      checkAll(
        arbInstant,
        arbOtpLength,
        arbTotpSecret,
        Arb.int(30..90), // time step
        Arb.int(0..3)    // future steps
      ) { time, otpLength, secret, timeStep, allowedFutureSteps ->

        val serverTimeStep = obtainCurrentTimeStep(Clock.fixed(time, ZoneOffset.UTC), timeStep)
        val serverTimeInSeconds = (serverTimeStep * timeStep)
        val clientClockUpperBound = serverTimeInSeconds + ((timeStep * (allowedFutureSteps + 1)) - 1)

        checkAll(Arb.long(serverTimeInSeconds..clientClockUpperBound)) { clientTime ->
          given(
            TestState(
              clock = Clock.fixed(Instant.ofEpochSecond(serverTimeInSeconds), ZoneOffset.UTC),
              timeStep = timeStep,
              otpLength = otpLength,
              allowedFutureTimeSteps = allowedFutureSteps
            )
          ) {
            val driftedClock = Clock.fixed(Instant.ofEpochSecond(clientTime), ZoneOffset.UTC)
            val userInputTotp = localTOTPGeneration(secret, driftedClock, otpLength, timeStep)
            val verificationResult = defaultTOTPService.verify(
              userInputTotp,
              secret,
              allowedFutureSteps = allowedFutureSteps
            )
            verificationResult should beInstanceOf<TOTPVerificationResult.Success>()
          }
        }
      }
    }

    /**
     * In this test we position the server clock at the very beginning of the time step. This is the lower bound.
     * We then calculate the end of the time step further in the future that is allowed. This is the upper bound.
     * We then calculate a random delay in the range of the lowest bound to upper bound inclusive.
     *
     * Example:
     *                    _____<- upper bound
     *                     T+1
     * lower bound->______
     *                T
     *         _____
     *          T-1
     */
    test("should validate TOTP within same time step ") {
      checkAll(
        arbInstant,
        arbOtpLength,
        arbTotpSecret,
        Arb.int(30..90), // time step
        Arb.int(0..3)    // future steps
      ) { time, otpLength, secret, timeStep, allowedFutureSteps ->

        val serverTimeStep = obtainCurrentTimeStep(Clock.fixed(time, ZoneOffset.UTC), timeStep)
        val serverTimeInSeconds = (serverTimeStep * timeStep)
        val clientClockUpperBound = serverTimeInSeconds + ((timeStep * (allowedFutureSteps + 1)) - 1)

        checkAll(Arb.long(serverTimeInSeconds..clientClockUpperBound)) { clientTime ->
          given(
            TestState(
              clock = Clock.fixed(Instant.ofEpochSecond(serverTimeInSeconds), ZoneOffset.UTC),
              timeStep = timeStep,
              otpLength = otpLength,
              allowedFutureTimeSteps = allowedFutureSteps
            )
          ) {
            val driftedClock = Clock.fixed(Instant.ofEpochSecond(clientTime), ZoneOffset.UTC)
            val userInputTotp = localTOTPGeneration(secret, driftedClock, otpLength, timeStep)
            val verificationResult = defaultTOTPService.verify(
              userInputTotp,
              secret,
              allowedFutureSteps = allowedFutureSteps
            )
            verificationResult should beInstanceOf<TOTPVerificationResult.Success>()
          }
        }
      }
    }
  }
})

private suspend fun given(state: TestState = TestState(), test: suspend TestState.(DefaultTOTPService) -> Unit) {
  with(state) {
    test(state.defaultTOTPService)
  }
}

data class TestState(
  val clock: Clock = Clock.systemUTC(),
  val timeStep: Int = 30,
  val otpLength: OTPLength = OTPLength.SIX,
  val digest: HMACDigest = HMACDigest.SHA1,
  val allowedPastTimeSteps: Int = 0,
  val allowedFutureTimeSteps: Int = 0,
  val issuer: Issuer = Issuer("Atlassian"),
) {

  private val totpGenerator = CustomTOTPGenerator(
    startTime = 0,
    timeStepSeconds = timeStep,
    otpLength = otpLength,
    digest = digest,
    clock = clock
  )

  val defaultTOTPService = DefaultTOTPService(
    totpGenerator,
    TOTPConfiguration(
      allowedPastSteps = allowedPastTimeSteps,
      allowedFutureSteps = allowedFutureTimeSteps
    )
  )
}

private fun obtainCurrentTimeStep(clock: Clock, timeStep: Int): Long {
  val startTime = 0
  return ((clock.millis() / 1000) - startTime) / timeStep
}

private fun localTOTPGeneration(
  totpSecret: TOTPSecret,
  clock: Clock,
  otpLength: OTPLength = OTPLength.SIX,
  timeStep: Int = 30
): TOTP {
  val generator = CustomHOTPGenerator(otpLength, HMACDigest.SHA1)
  val t: Long = obtainCurrentTimeStep(clock, timeStep)
  val hotp = generator.generate(totpSecret.value, t)
  return TOTP(hotp.value)
}

private data class QueryParam(val name: String, val value: String)

private fun URI.queryParams(): List<QueryParam> = splitQuery(this.query)

private fun splitQuery(query: String): List<QueryParam> =
  if (query.isEmpty()) {
    emptyList()
  } else {
    query.split("&").map { pair ->
      val (name, value) = pair.split("=", limit = 2)
      QueryParam(name, value)
    }
  }


