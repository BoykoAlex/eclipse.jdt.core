/*******************************************************************************
 * Copyright (c) 2007 BEA Systems, Inc.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package targets.model.pb;

import java.util.List;
import targets.model.pa.IA;

public class AB implements IB, IA {
	private class E {
		
	}
	
	protected List<IA> _fieldListIA;
	
	public String methodIAString(int int1) { return (new E()).toString(); }
}
