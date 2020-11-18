/*
 *  (C) Copyright 2020 Radix DLT Ltd
 *
 *  Radix DLT Ltd licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License.  You may obtain a copy of the
 *  License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *  either express or implied.  See the License for the specific
 *  language governing permissions and limitations under the License.
 */

package com.radixdlt.consensus.safety;

import com.google.common.hash.HashCode;
import com.radixdlt.consensus.bft.Self;
import com.radixdlt.consensus.bft.VerifiedVertex;
import com.radixdlt.consensus.bft.View;
import com.radixdlt.consensus.HighQC;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.consensus.HashSigner;
import com.radixdlt.consensus.Proposal;
import com.radixdlt.consensus.QuorumCertificate;
import com.radixdlt.consensus.TimestampedVoteData;
import com.radixdlt.consensus.UnverifiedVertex;
import com.radixdlt.consensus.ViewTimeout;
import com.radixdlt.consensus.ViewTimeoutData;
import com.radixdlt.consensus.BFTHeader;
import com.radixdlt.consensus.Vote;
import com.radixdlt.consensus.VoteData;
import com.radixdlt.consensus.bft.BFTNode;
import com.radixdlt.consensus.safety.SafetyState.Builder;
import com.radixdlt.crypto.ECDSASignature;

import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages safety of the protocol.
 */
public final class SafetyRules {
	private static final Logger logger = LogManager.getLogger();

	private final BFTNode self;
	private final Hasher hasher;
	private final HashSigner signer;

	private SafetyState state;

	public SafetyRules(
		@Self BFTNode self,
		SafetyState initialState,
		Hasher hasher,
		HashSigner signer
	) {
		this.self = self;
		this.state = Objects.requireNonNull(initialState);
		this.hasher = Objects.requireNonNull(hasher);
		this.signer = Objects.requireNonNull(signer);
	}

	/**
	 * Create a signed proposal from a vertex
	 * @param proposedVertex vertex to sign
	 * @param highestCommittedQC highest known committed QC
	 * @return signed proposal object for consensus
	 */
	public Proposal signProposal(UnverifiedVertex proposedVertex, QuorumCertificate highestCommittedQC) {
		final HashCode vertexHash = this.hasher.hash(proposedVertex);
		ECDSASignature signature = this.signer.sign(vertexHash);
		return new Proposal(proposedVertex, highestCommittedQC, this.self, signature);
	}

	private static VoteData constructVoteData(VerifiedVertex proposedVertex, BFTHeader proposedHeader) {
		final BFTHeader parent = proposedVertex.getParentHeader();

		// Add a vertex to commit if creating a quorum for the proposed vertex would
		// create three consecutive qcs.
		final BFTHeader toCommit;
		if (proposedVertex.touchesGenesis()
			|| !proposedVertex.hasDirectParent()
			|| !proposedVertex.parentHasDirectParent()
		) {
			toCommit = null;
		} else {
			toCommit = proposedVertex.getGrandParentHeader();
		}

		return new VoteData(proposedHeader, parent, toCommit);
	}

	/**
	 * Sign a view timeout for the specified view.
	 */
	public ViewTimeout viewTimeout(View view, HighQC highQC) {
		long epoch = highQC.highestQC().getProposed().getLedgerHeader().getEpoch();
		ViewTimeoutData viewTimeoutData = ViewTimeoutData.from(this.self, epoch, view);
		ECDSASignature signature = this.signer.sign(this.hasher.hash(viewTimeoutData));
		return ViewTimeout.from(viewTimeoutData, highQC, signature);
	}

	/**
	 * Vote for a proposed vertex while ensuring that safety invariants are upheld.
	 *
	 * @param proposedVertex The proposed vertex
	 * @param proposedHeader results of vertex execution
	 * @param timestamp timestamp to use for the vote in milliseconds since epoch
	 * @param highQC our current sync state
	 * @return A vote result containing the vote and any committed vertices
	 */
	public Optional<Vote> voteFor(VerifiedVertex proposedVertex, BFTHeader proposedHeader, long timestamp, HighQC highQC) {
		// ensure vertex does not violate earlier votes
		if (proposedVertex.getView().compareTo(this.state.getLastVotedView()) <= 0) {
			logger.warn("Violates earlier vote at view {}", this.state.getLastVotedView());
			return Optional.empty();
		}

		if (proposedVertex.getParentHeader().getView().compareTo(this.state.getLockedView()) < 0) {
			logger.warn("Does not respect locked view {}", this.state.getLockedView());
			return Optional.empty();
		}

		Builder safetyStateBuilder = this.state.toBuilder();
		safetyStateBuilder.lastVotedView(proposedVertex.getView());
		// pre-commit phase on consecutive qc's proposed vertex
		if (proposedVertex.getGrandParentHeader().getView().compareTo(this.state.getLockedView()) > 0) {
			safetyStateBuilder.lockedView(proposedVertex.getGrandParentHeader().getView());
		}

		final VoteData voteData = constructVoteData(proposedVertex, proposedHeader);
		final TimestampedVoteData timestampedVoteData = new TimestampedVoteData(voteData, timestamp);

		final HashCode voteHash = hasher.hash(timestampedVoteData);

		this.state = safetyStateBuilder.build();

		// TODO make signing more robust by including author in signed hash
		ECDSASignature signature = this.signer.sign(voteHash);
		Vote vote = new Vote(this.self, timestampedVoteData, signature, highQC);
		return Optional.of(vote);
	}
}
