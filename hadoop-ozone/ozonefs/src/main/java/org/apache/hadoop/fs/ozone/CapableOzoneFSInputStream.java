/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs.ozone;

import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.StreamCapabilities;
import org.apache.hadoop.util.StringUtils;

import java.io.InputStream;

final class CapableOzoneFSInputStream extends OzoneFSInputStream
    implements StreamCapabilities {

  CapableOzoneFSInputStream(InputStream inputStream, Statistics statistics) {
    super(inputStream, statistics);
  }

  @Override
  public boolean hasCapability(String capability) {
    switch (StringUtils.toLowerCase(capability)) {
    case OzoneStreamCapabilities.READBYTEBUFFER:
      return true;
    default:
      return false;
    }
  }
}
