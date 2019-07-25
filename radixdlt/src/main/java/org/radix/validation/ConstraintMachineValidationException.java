package org.radix.validation;

import com.radixdlt.atoms.ImmutableAtom;
import java.util.Objects;
import org.radix.atoms.Atom;
import com.radixdlt.atoms.DataPointer;
import org.radix.exceptions.ValidationException;

/**
 * An exception during validation of an {@link Atom}
 */
public class ConstraintMachineValidationException extends ValidationException {
	private final ImmutableAtom atom;
	private final DataPointer dataPointer;

	public ConstraintMachineValidationException(ImmutableAtom atom, String message, DataPointer dataPointer) {
		super(message);

		Objects.requireNonNull(atom);
		Objects.requireNonNull(dataPointer);

		dataPointer.validateExists(atom);

		this.atom = atom;
		this.dataPointer = dataPointer;
	}

	public String getPointerToIssue() {
		return dataPointer.toString();
	}

	public ImmutableAtom getAtom() {
		return atom;
	}
}