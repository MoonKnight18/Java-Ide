/*
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 * <p>
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package io.github.rosemoe.sora.textmate.core.internal.grammar.parser;

import io.github.rosemoe.sora.textmate.core.internal.parser.PListObject;

import java.util.Map;

public class PListGrammar extends PListObject {

    public PListGrammar(PListObject parent, boolean valueAsArray) {
        super(parent, valueAsArray);
    }

    @Override
    protected Map<String, Object> createRaw() {
        return new Raw();
    }
}
