/*
 * Copyright (c) 2015-2018 Open Baton (http://openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openbaton.monitoring.interfaces;

import org.openbaton.plugin.interfaces.Plugin;

/** Created by lto on 15/10/15. */
public abstract class MonitoringPlugin extends Plugin
    implements VirtualisedResourceFaultManagement, VirtualisedResourcesPerformanceManagement {

  protected MonitoringPlugin() {
    super();
  }
}
