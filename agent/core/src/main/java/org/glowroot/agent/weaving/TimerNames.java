/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.weaving;

import org.glowroot.agent.plugin.api.weaving.Pointcut;

// this is just to support deprecated timerName() attribute for a bit
public class TimerNames {

    private TimerNames() {}

    @SuppressWarnings("deprecation")
    public static String getTimerName(Pointcut pointcut) {
        String timerName = pointcut.timer();
        if (timerName.isEmpty()) {
            return pointcut.timerName();
        } else {
            return timerName;
        }
    }
}
