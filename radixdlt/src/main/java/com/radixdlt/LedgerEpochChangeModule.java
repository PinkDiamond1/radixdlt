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

package com.radixdlt;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.radixdlt.consensus.Hasher;
import com.radixdlt.epochs.EpochChangeManager;
import com.radixdlt.epochs.EpochChangeManager.EpochsLedgerUpdateSender;
import com.radixdlt.epochs.EpochChangeSender;
import com.radixdlt.ledger.StateComputerLedger.LedgerUpdateSender;

/**
 * Adds epoch change functionality to the ledger
 */
public class LedgerEpochChangeModule extends AbstractModule {
	@ProvidesIntoSet
	private LedgerUpdateSender epochChangeManager(
		EpochChangeSender sender,
		EpochsLedgerUpdateSender epochsLedgerUpdateSender,
		Hasher hasher
	) {
		return new EpochChangeManager(sender, epochsLedgerUpdateSender, hasher);
	}
}
