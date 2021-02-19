/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.environment.deterministic;

import com.radixdlt.consensus.bft.BFTNode;
import com.radixdlt.environment.RemoteEventProcessor;
import com.radixdlt.mempool.MempoolAdd;

import javax.inject.Inject;

public final class DeterministicMempoolProcessor implements DeterministicMessageProcessor {
	private final RemoteEventProcessor<MempoolAdd> remoteEventProcessor;

	@Inject
	public DeterministicMempoolProcessor(
		RemoteEventProcessor<MempoolAdd> remoteEventProcessor
	) {
		this.remoteEventProcessor = remoteEventProcessor;
	}

	@Override
	public void start() {
		// No-op
	}

	@Override
	public void handleMessage(BFTNode origin, Object o) {
		if (o instanceof MempoolAdd) {
			this.remoteEventProcessor.process(origin, (MempoolAdd) o);
		} else {
			throw new IllegalArgumentException("Unknown message type: " + o.getClass().getName());
		}
	}
}
