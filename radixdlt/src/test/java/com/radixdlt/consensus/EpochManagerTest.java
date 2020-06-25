package com.radixdlt.consensus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.radixdlt.consensus.liveness.Pacemaker;
import com.radixdlt.consensus.liveness.ProposerElection;
import com.radixdlt.consensus.validators.Validator;
import com.radixdlt.consensus.validators.ValidatorSet;
import com.radixdlt.counters.SystemCounters;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.mempool.Mempool;
import org.junit.Test;

public class EpochManagerTest {
	@Test
	public void when_next_epoch__then_should_not_fail() {
		ECKeyPair keyPair = mock(ECKeyPair.class);
		when(keyPair.getPublicKey()).thenReturn(mock(ECPublicKey.class));
		VertexStore vertexStore = mock(VertexStore.class);
		when(vertexStore.getHighestQC()).thenReturn(mock(QuorumCertificate.class));

		EpochManager epochManager = new EpochManager(
			mock(Mempool.class),
			mock(BFTEventSender.class),
			() -> mock(Pacemaker.class),
			(v, qc) -> vertexStore,
			proposers -> mock(ProposerElection.class),
			mock(Hasher.class),
			keyPair,
			mock(SystemCounters.class)
		);

		Validator validator = mock(Validator.class);
		when(validator.nodeKey()).thenReturn(mock(ECPublicKey.class));
		ValidatorSet validatorSet = mock(ValidatorSet.class);
		when(validatorSet.getValidators()).thenReturn(ImmutableSet.of(validator));
		epochManager.processEpochChange(new EpochChange(VertexMetadata.ofGenesisAncestor(), validatorSet));
	}
}