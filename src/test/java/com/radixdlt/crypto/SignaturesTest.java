package com.radixdlt.crypto;

import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Random;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

public class SignaturesTest {

	interface SchnorrSignature extends Signature {
		// Dummy type for tests
	}

    @Test
    public void from_engine___verify_that_ecdsa_is_default_signature_scheme() {
        Signatures emptySignatures = DefaultSignatures.emptySignatures();
        assertEquals(SignatureScheme.ECDSA, emptySignatures.signatureScheme());
    }

    @Test
    public void from_engine___verify_that_ecdsasignature_specifies_correct_scheme() {
	    ECDSASignature signature = new ECDSASignature();
        assertEquals(SignatureScheme.ECDSA, signature.signatureScheme());
    }

    @Test
    public void from_engine___well_formatted_tostring_of_signaturescheme() {
        assertThat(SignatureScheme.ECDSA.toString(), is("ecdsa"));
    }

    @Test
    public void from_engine___verify_that_default_signature_scheme_is_ecdsa() {
        Signatures emptySignatures = DefaultSignatures.emptySignatures();

        assertEquals(SignatureScheme.ECDSA, emptySignatures.signatureScheme());
        assertTrue(emptySignatures.isEmpty());
        ECDSASignature mockSignature = mock(ECDSASignature.class);
        ECPublicKey publicKey = mock(ECPublicKey.class);

        Signatures nonEmptySignatures = emptySignatures.concatenate(publicKey, mockSignature);
        assertEquals(1, nonEmptySignatures.count());

        nonEmptySignatures.signatureScheme();
    }

    @Test
    public void from_engine___verify_that_a_single_invalid_signature_does_fails_to_verify() {
        Signatures single = new ECDSASignatures(publicKey(), randomInvalidSignature());
        assertEquals(1, single.count());
        assertFalse(single.hasSignedMessage(hashOfMessage("Fubar"), 1));
    }

    @Test
    public void from_engine___verify_that_multiple_invalid_signature_does_fails_to_verify() {
        Signatures multiple = new ECDSASignatures(ImmutableMap.of(publicKey(), randomInvalidSignature(), publicKey(), randomInvalidSignature()));
        assertEquals(2, multiple.count());
        assertFalse(multiple.hasSignedMessage(hashOfMessage("Fubar"), 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void from_engine___verify_that_we_crash_if_we_try_to_concatenate_signatures_with_a_signature_of_incompatible_type_schnorr_to_ecdsa() {
        Signatures emptySignatures = DefaultSignatures.emptySignatures();
        assertEquals(SignatureScheme.ECDSA, emptySignatures.signatureScheme());
        assertTrue(emptySignatures.isEmpty());
        SchnorrSignature schnorr = mock(SchnorrSignature.class);
        ECPublicKey publicKey = mock(ECPublicKey.class);
        emptySignatures.concatenate(publicKey, schnorr);
        fail();
    }

    @Test(expected = IllegalArgumentException.class)
    public void from_engine___verify_that_we_crash_if_we_try_to_concatenate_signatures_with_a_signature_of_incompatible_type_ecdsa_to_schnorr() {
        Signatures emptySignatures = DefaultSignatures.emptySignatures();
        assertTrue(emptySignatures.isEmpty());
        SchnorrSignature schnorrSignature = mock(SchnorrSignature.class);
        ECPublicKey publicKey = mock(ECPublicKey.class);
        emptySignatures.concatenate(publicKey, schnorrSignature);
        fail();
    }

    @Test
    public void from_engine___test_that_we_can_bulk_verify_signatures_verify_all_two() throws CryptoException {
        test_that_we_can_bulk_verify_signatures(
                2,
                true,
                2,
                0
        );
    }

    @Test
    public void from_engine___test_that_we_can_bulk_verify_signatures_verify_at_least_N_of_M() throws CryptoException {
        for (int valid = 0; valid < 5; valid++) {
            for (int invalid = 0; invalid < 5; invalid++) {
                for (int threshold = 0; threshold <= (valid + invalid); threshold++) {

                    boolean isExpectedToMeetThreshold = valid >= threshold;
                    test_that_we_can_bulk_verify_signatures(
                            threshold,
                            isExpectedToMeetThreshold,
                            valid,
                            invalid
                    );
                }

            }
        }
    }

    @Test
    public void from_engine___well_formatted_tostring() {
        Signature dummySignature = randomInvalidSignature();
        Signatures signatures = DefaultSignatures.single(publicKey(), dummySignature);
        String tostring = signatures.toString();

        Assert.assertThat(tostring, containsString(ECDSASignature.class.getSimpleName()));
        Assert.assertThat(tostring, containsString(dummySignature.toString()));
    }

    private void test_that_we_can_bulk_verify_signatures(
            int thresholdNumberOfValidSignatures,
            boolean isExpectedToMeetThreshold,
            int numberOfValidSignaturesToCreate,
            int numberOfInvalidSignaturesToCreate
    ) throws CryptoException {
        if (thresholdNumberOfValidSignatures > (numberOfValidSignaturesToCreate + numberOfInvalidSignaturesToCreate)) {
            throw new IllegalArgumentException(
                    "The sum of #valid + #invalid signatures should >= 'thresholdNumberOfValidSignatures'"
            );
        }
        Signatures signatures = DefaultSignatures.emptySignatures();
        Hash hashedMessage = hashOfMessage("You must do what you feel is right of course");
        for (int i = 0; i < numberOfValidSignaturesToCreate + numberOfInvalidSignaturesToCreate; i++) {
            ECKeyPair keyPair = new ECKeyPair();
            assertNotNull(keyPair);
            final Signature signature;
            boolean shouldSignatureBeValid = i >= numberOfInvalidSignaturesToCreate;
            if (shouldSignatureBeValid) {
                signature = keyPair.sign(hashedMessage);
            } else {
                signature = randomInvalidSignature();
            }
            assertNotNull(signature);

            signatures = signatures.concatenate(keyPair.getPublicKey(), signature);
        }

        assertEquals((numberOfInvalidSignaturesToCreate + numberOfValidSignaturesToCreate), signatures.count());
        boolean doesSignatureMeetValidityThreshold = signatures.hasSignedMessage(hashedMessage, thresholdNumberOfValidSignatures);
        assertEquals(isExpectedToMeetThreshold, doesSignatureMeetValidityThreshold);
    }

    private ECPublicKey publicKey() {
        return keyPair().getPublicKey();
    }

    private ECKeyPair keyPair() {
        try {
            return new ECKeyPair();
        } catch (CryptoException e) {
        	throw new IllegalStateException("Failed to create keyPair", e);
        }
    }

    private ECDSASignature randomInvalidSignature() {
        Supplier<BigInteger> randomBigInt = () -> BigInteger.valueOf(new Random().nextLong());
        return new ECDSASignature(randomBigInt.get(), randomBigInt.get());
    }

    private Hash hashOfMessage(String message) {
        return new Hash(Hash.hash256(message.getBytes()));
    }
}
