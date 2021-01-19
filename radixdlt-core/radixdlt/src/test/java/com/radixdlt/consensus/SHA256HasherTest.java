package com.radixdlt.consensus;

import com.google.common.hash.HashCode;
import com.radixdlt.atommodel.Atom;
import com.radixdlt.atomos.RRIParticle;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.identifiers.RRI;
import com.radixdlt.identifiers.RadixAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SHA256HasherTest {

    private final Hasher hasher = Sha256Hasher.withDefaultSerialization();

    @Test
    public void hasher_test_atom() {
        Atom atom = new Atom();
        HashCode hash = hasher.hash(atom);
        assertIsNotRawDSON(hash);
        String hashHex = hash.toString();
        assertEquals("b0aace23265c295eb13464b5b97cf57d1a227a02c4c7042ab7daae1df1eb6e6a", hashHex);
    }

    @Test
    public void hasher_test_particle() {
        RadixAddress address = RadixAddress.from("JEbhKQzBn4qJzWJFBbaPioA2GTeaQhuUjYWkanTE6N8VvvPpvM8");
        RRI rri = RRI.of(address, "FOOBAR");
        RRIParticle particle = new RRIParticle(rri);
        HashCode hash = hasher.hash(particle);
        assertIsNotRawDSON(hash);
        String hashHex = hash.toString();
        assertEquals("6cbb92f0cb433da00f3f04f5c03b27bd1cb7f7724fe295c06eaeda15b61bb70a", hashHex);
    }

    private void assertIsNotRawDSON(HashCode hash) {
        String hashHex = hash.toString();
        // CBOR/DSON encoding of an object starts with "bf" and ends with "ff", so we are here making
        // sure that Hash of the object is not just the DSON output, but rather a 256 bit hash digest of it.
        // the probability of 'accidentally' getting getting these prefixes and suffixes anyway is minimal (1/2^16)
        // for any DSON bytes as argument.
        assertFalse(hashHex.startsWith("bf") && hashHex.endsWith("ff"));
    }
}
