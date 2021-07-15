/*
 * (C) Copyright 2021 Radix DLT Ltd
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
 *
 */

package com.radixdlt.application.validators.construction;

import com.radixdlt.application.system.state.EpochData;
import com.radixdlt.atom.ActionConstructor;
import com.radixdlt.atom.TxBuilder;
import com.radixdlt.atom.TxBuilderException;
import com.radixdlt.atom.actions.UpdateValidatorOwner;
import com.radixdlt.application.validators.state.ValidatorOwnerCopy;

import java.util.OptionalLong;

public class UpdateValidatorOwnerConstructor implements ActionConstructor<UpdateValidatorOwner> {
	@Override
	public void construct(UpdateValidatorOwner action, TxBuilder txBuilder) throws TxBuilderException {
		txBuilder.down(ValidatorOwnerCopy.class, action.validatorKey());
		var curEpoch = txBuilder.readSystem(EpochData.class);
		txBuilder.up(new ValidatorOwnerCopy(
			OptionalLong.of(curEpoch.getEpoch() + 1), action.validatorKey(), action.getOwnerAddress()
		));
		txBuilder.end();
	}
}
