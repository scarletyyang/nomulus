// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools.params;

import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PhoneNumberParameter}. */
@RunWith(JUnit4.class)
public class PhoneNumberParameterTest {
  private final OptionalPhoneNumberParameter instance = new OptionalPhoneNumberParameter();

  @Test
  public void testConvert_e164() {
    assertThat(instance.convert("+1.2125550777")).hasValue("+1.2125550777");
  }

  @Test
  public void testConvert_sillyString_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("foo"));
  }

  @Test
  public void testConvert_empty_returnsEmpty() {
    assertThat(instance.convert("")).isEmpty();
  }

  @Test
  public void testConvert_nullString_returnsEmpty() {
    assertThat(instance.convert("null")).isEmpty();
  }
}
