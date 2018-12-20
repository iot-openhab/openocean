/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.enocean.internal.eep.A5_14;

import static org.openhab.binding.enocean.internal.EnOceanBindingConstants.CHANNEL_CONTACT;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.enocean.internal.messages.ERP1Message;

/**
 * Single Input Contact (Window/Door), Supply voltage monitor
 *
 * @author Dominik Krickl-Vorreiter - Initial contribution
 */
public class A5_14_01 extends A5_14 {

    public A5_14_01(ERP1Message packet) {
        super(packet);
    }

    private State getContact() {
        boolean ct = getBit(getDB_0(), 0);

        return ct ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
    }

    @Override
    protected State convertToStateImpl(String channelId, String channelTypeId, State currentState,
            Configuration config) {
        switch (channelId) {
            case CHANNEL_CONTACT:
                return getContact();
        }

        return super.convertToStateImpl(channelId, channelTypeId, currentState, config);
    }
}
