package com.radixdlt.sanitytestsuite;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.radixdlt.consensus.Sha256Hasher;
import com.radixdlt.crypto.ECDSASignature;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.utils.Bytes;
import com.radixdlt.utils.JSONFormatter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;



public class SanityTestSuiteTestExecutor {

	private static final Logger log = LogManager.getLogger();

	private SanityTestSuiteRoot sanityTestSuiteRootFromFileNamed(String sanityTestJSONFileName) {
		Gson gson = new Gson();
		JsonReader reader = null;
		try {
			ClassLoader classLoader = getClass().getClassLoader();
			File file = new File(classLoader.getResource(sanityTestJSONFileName).getFile());

			// Compare saved hash in file with calculated hash of test.
			String jsonFileContent = Files.asCharSource(file, StandardCharsets.UTF_8).read();
			JSONObject sanityTestSuiteRootAsJsonObject = new JSONObject(jsonFileContent);
			String sanityTestSuiteSavedHash = sanityTestSuiteRootAsJsonObject.getString("hashOfSuite");
			JSONObject sanityTestSuiteAsJsonObject = sanityTestSuiteRootAsJsonObject.getJSONObject("suite");
			String sanityTestSuiteAsJsonStringPretty = JSONFormatter.sortPrettyPrintJSONString(sanityTestSuiteAsJsonObject.toString(4));
			Hasher hasher = Sha256Hasher.withDefaultSerialization();
			HashCode calculatedHashOfSanityTestSuite = hasher.hash(sanityTestSuiteAsJsonStringPretty);
			assertEquals(sanityTestSuiteSavedHash, calculatedHashOfSanityTestSuite.toString());

			FileReader fileReader = new FileReader(file);
			reader = new JsonReader(fileReader);
		} catch (Exception e) {
			throw new IllegalStateException("failed to load test vectors, e: " + e);
		}

		SanityTestSuiteRoot sanityTestSuiteRoot = gson.fromJson(reader, SanityTestSuiteRoot.class);

		return sanityTestSuiteRoot;

	}

	private static final String prettyJsonStringFromObject(Object object) {
		Gson gson = new Gson();
		String jsonStringPretty = JSONFormatter.sortPrettyPrintJSONString(gson.toJson(object));

		return jsonStringPretty;
	}

	private SanityTestSuiteRoot sanityTestSuiteRootFromFile() {
		return sanityTestSuiteRootFromFileNamed("sanity_test_suite.json");
	}

	private static final String TEST_SCENARIO_HASHING = "hashing";
	private static final String TEST_SCENARIO_RADIXHASHING = "radix_hashing";
	private static final String TEST_SCENARIO_KEYGEN = "secp256k1";
	private static final String TEST_SCENARIO_KEYSIGN = "ecdsa_signing";
	private static final String TEST_SCENARIO_KEYVERIFY = "ecdsa_verification";
	private static final String TEST_SCENARIO_JSON_ROUNDTRIP_RADIX_PARTICLES = "json_radix_particles";


	private static <T> T cast(Object object, Class<T> toType) {
		Gson gson = new Gson();
		String jsonFromObj = gson.toJson(object);
		return gson.fromJson(jsonFromObj, toType);
	}

	private static byte[] sha256Hash(byte[] bytes) {
		MessageDigest hasher = null;
		try {
			hasher = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Failed to run test, found no hasher", e);
		}
		hasher.update(bytes);
		return hasher.digest();
	}



	static final class HashingTestVector {

		static final class Expected {
			private String hash;
		}

		static final class Input {
			private String stringToHash;
			byte[] bytesToHash() {
				return this.stringToHash.getBytes(StandardCharsets.UTF_8);
			}
		}

		private Expected expected;
		private Input input;
	}

	private void testScenarioHashing(SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario) {
		assertEquals(TEST_SCENARIO_HASHING, scenario.identifier);

		BiConsumer<HashingTestVector, Integer> testVectorRunner = (vector, vectorIndex) -> {
			String hashHex = Bytes.toHexString(sha256Hash(vector.input.bytesToHash()));

			assertEquals(String.format("Test vector at index %d failed.", vectorIndex), vector.expected.hash, hashHex);
		};

		for (int testVectorIndex = 0; testVectorIndex < scenario.tests.vectors.size(); ++testVectorIndex) {
			UnknownTestVector untypedVector = scenario.tests.vectors.get(testVectorIndex);
			testVectorRunner.accept(cast(untypedVector, HashingTestVector.class), testVectorIndex);
		}
	}


	static final class RadixHashingTestVector {
		static final class Expected {
			private String hashOfHash;
		}

		static final class Input {
			private String stringToHash;
			byte[] bytesToHash() {
				return this.stringToHash.getBytes(StandardCharsets.UTF_8);
			}
		}

		private Expected expected;
		private Input input;
	}
	private void testScenarioRadixHashing(SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario) {
		assertEquals(TEST_SCENARIO_RADIXHASHING, scenario.identifier);

		BiConsumer<RadixHashingTestVector, Integer> testVectorRunner = (vector, vectorIndex) -> {
			String hashHex = Bytes.toHexString(HashUtils.sha256(vector.input.bytesToHash()).asBytes());
			assertEquals(String.format("Test vector at index %d failed.", vectorIndex), vector.expected.hashOfHash, hashHex);
		};

		for (int testVectorIndex = 0; testVectorIndex < scenario.tests.vectors.size(); ++testVectorIndex) {
			UnknownTestVector untypedVector = scenario.tests.vectors.get(testVectorIndex);
			testVectorRunner.accept(cast(untypedVector, RadixHashingTestVector.class), testVectorIndex);
		}
	}


	static final class KeyGenTestVector {
		static final class Expected {
			private String uncompressedPublicKey;
		}

		static final class Input  {
			private String privateKey;
		}

		private Expected expected;
		private Input input;
	}
	private void testScenarioKeyGen(SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario) {
		assertEquals(TEST_SCENARIO_KEYGEN, scenario.identifier);

		BiConsumer<KeyGenTestVector, Integer> testVectorRunner = (vector, vectorIndex) -> {

			ECPublicKey publicKey = null;
			ECPublicKey expectedPublicKey = null;

			try {
				publicKey = ECKeyPair.fromPrivateKey(Bytes.fromHexString(vector.input.privateKey)).getPublicKey();
				expectedPublicKey = ECPublicKey.fromBytes(Bytes.fromHexString(vector.expected.uncompressedPublicKey));
			} catch (Exception e) {
				Assert.fail(String.format("Test vector at index %d failed. Failed to get public key: " + e, vectorIndex));
			}

			assertNotNull(publicKey);
			assertTrue(publicKey.equals(expectedPublicKey));
		};

		for (int testVectorIndex = 0; testVectorIndex < scenario.tests.vectors.size(); ++testVectorIndex) {
			UnknownTestVector untypedVector = scenario.tests.vectors.get(testVectorIndex);
			testVectorRunner.accept(cast(untypedVector, KeyGenTestVector.class), testVectorIndex);
		}
	}

	static final class KeySignTestVector {
		static final class Input {
			private String privateKey;
			private String messageToSign;
			private byte[] privateKeyBytes() {
				return Bytes.fromHexString(this.privateKey);
			}
			private byte[] hashedMessageToSign() {
				byte[] unhashedEncodedMessage = messageToSign.getBytes(StandardCharsets.UTF_8);
				return sha256Hash(unhashedEncodedMessage);
			}
		}
		static final class Expected {
			static final class Signature {
				private String r;
				private String s;
				private String der;
			}
			private String k;
			private Signature signature;
		}

		private Expected expected;
		private Input input;
	}
	private void testScenarioKeySign(SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario) {
		assertEquals(TEST_SCENARIO_KEYSIGN, scenario.identifier);

		BiConsumer<KeySignTestVector, Integer> testVectorRunner = (testVector, testVectorIndex) -> {

			ECKeyPair keyPair = null;
			try {
				keyPair = ECKeyPair.fromPrivateKey(testVector.input.privateKeyBytes());
			} catch (Exception e) {
				Assert.fail(String.format("Test vector at index %d failed. Failed to construct private key from hex: " + e, testVectorIndex));
			}
			ECDSASignature signature = keyPair.sign(testVector.input.hashedMessageToSign(), true, true);
			assertEquals(
					String.format("Test vector at index %d failed. Signature.R mismatch", testVectorIndex),
					testVector.expected.signature.r,
					signature.getR().toString(16)
			);
			assertEquals(
					String.format("Test vector at index %d failed. Signature.S mismatch", testVectorIndex),
					testVector.expected.signature.s,
					signature.getS().toString(16)
			);
		};

		for (int testVectorIndex = 0; testVectorIndex < scenario.tests.vectors.size(); ++testVectorIndex) {
			UnknownTestVector untypedVector = scenario.tests.vectors.get(testVectorIndex);
			testVectorRunner.accept(
					cast(untypedVector, KeySignTestVector.class),
					testVectorIndex
			);
		}
	}

	static final class KeyVerifyTestVector {
		static final class Input {
			private String comment;
			private int wycheProofVectorId;
			private String msg;
			private String publicKeyUncompressed;
			private String signatureDerEncoded;

			private byte[] hashedMessageToVerify() {
				return sha256Hash(Bytes.fromHexString(this.msg));
			}
		}
		static final class Expected {
			private boolean isValid;
		}
		private Expected expected;
		private Input input;
	}
	private void testScenarioKeyVerify(SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario) {
		assertEquals(TEST_SCENARIO_KEYVERIFY, scenario.identifier);

		BiConsumer<KeyVerifyTestVector, Integer> testVectorRunner = (testVector, testVectorIndex) -> {

			ECPublicKey publicKey = null;
			try {
				publicKey = ECPublicKey.fromBytes(Bytes.fromHexString(testVector.input.publicKeyUncompressed));
			} catch (Exception e) {
				Assert.fail(String.format("Test vector at index %d failed. Failed to construct public key from hex: " + e, testVectorIndex));
			}
			ECDSASignature signature = ECDSASignature.decodeFromDER(Bytes.fromHexString(testVector.input.signatureDerEncoded));

			byte[] msg = testVector.input.hashedMessageToVerify();

			assertEquals(
					String.format("Test vector at index %d failed. Vector: %s", testVectorIndex, prettyJsonStringFromObject(testVector)),
					testVector.expected.isValid,
					publicKey.verify(msg, signature)
			);

		};

		for (int testVectorIndex = 4; testVectorIndex < scenario.tests.vectors.size(); ++testVectorIndex) {
			UnknownTestVector untypedVector = scenario.tests.vectors.get(testVectorIndex);
			testVectorRunner.accept(
					cast(untypedVector, KeyVerifyTestVector.class),
					testVectorIndex
			);
		}
	}

	private void testScenarioJsonRoundTripRadixParticles(SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario) {
		assertEquals(TEST_SCENARIO_JSON_ROUNDTRIP_RADIX_PARTICLES, scenario.identifier);
	}


	private Map<String, Consumer<SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario>> makeScenarioRunnerMap() {
		HashMap<String, Consumer<SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario>> map = new HashMap<>();

		map.put(TEST_SCENARIO_HASHING, this::testScenarioHashing);
		map.put(TEST_SCENARIO_RADIXHASHING, this::testScenarioRadixHashing);
		map.put(TEST_SCENARIO_KEYGEN, this::testScenarioKeyGen);
		map.put(TEST_SCENARIO_KEYSIGN, this::testScenarioKeySign);
		map.put(TEST_SCENARIO_KEYVERIFY, this::testScenarioKeyVerify);
		map.put(TEST_SCENARIO_JSON_ROUNDTRIP_RADIX_PARTICLES, this::testScenarioJsonRoundTripRadixParticles);

		return ImmutableMap.copyOf(map);
	}

	@Test
	public void test_sanity_suite() {
		SanityTestSuiteRoot sanityTestSuiteRoot = sanityTestSuiteRootFromFile();
		Map<String, Consumer<SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario>> scenarioRunnerMap = makeScenarioRunnerMap();
		assertEquals(
				scenarioRunnerMap.keySet(),
				sanityTestSuiteRoot.suite.scenarios.stream().map(s -> s.identifier).collect(Collectors.toSet())
		);

		for (SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario scenario : sanityTestSuiteRoot.suite.scenarios) {
			Consumer<SanityTestSuiteRoot.SanityTestSuite.SanityTestScenario> scenarioRunner = scenarioRunnerMap.get(scenario.identifier);
			// Run test scenario
			log.debug(String.format("🔮 Running scenario: %s", scenario.name));

			try {
				scenarioRunner.accept(scenario);
				log.info(String.format("✅ Test of scenario '%s' passed", scenario.name));
			} catch (AssertionError testAssertionError) {

				String failDebugInfo = String.format(
								"\n⚠️⚠️⚠️\nFailed test scenario: '%s'\n" +
								"Identifier: '%s'\n" +
								"Purpose of scenario: '%s'\n" +
								"Troubleshooting: '%s'\n" +
								"Implementation info: '%s'\n" +
								"Test vectors found at: '%s'\n" +
								"Test vectors modified?: '%s'\n" +
								"Failure reason: '%s'\n⚠️⚠️⚠️\n",
						scenario.name,
						scenario.identifier,
						scenario.description.purpose,
						scenario.description.troubleshooting,
						scenario.description.implementationInfo,
						scenario.tests.source.link,
						scenario.tests.source.modifiedByTool == null ? "NO" : "YES, modified with tool (see 'expression' for how): " + scenario.tests.source.modifiedByTool.tool.link,
						testAssertionError.getLocalizedMessage()
				);

				log.error(failDebugInfo);

				Assert.fail(failDebugInfo);
			}
		}
	}
}
