/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.avro;

import static org.apache.avro.generic.GenericData.Record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.DatumWriter;
import org.apache.iceberg.Files;
import org.apache.iceberg.Schema;
import org.apache.iceberg.mapping.MappedField;
import org.apache.iceberg.mapping.MappedFields;
import org.apache.iceberg.mapping.MappingUtil;
import org.apache.iceberg.mapping.NameMapping;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Comparators;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
public class TestAvroNameMapping extends TestAvroReadProjection {
  @Test
  public void testMapProjections() throws IOException {
    Schema writeSchema =
        new Schema(
            Types.NestedField.required(0, "id", Types.LongType.get()),
            Types.NestedField.optional(
                5,
                "location",
                Types.MapType.ofOptional(
                    6,
                    7,
                    Types.StringType.get(),
                    Types.StructType.of(
                        Types.NestedField.required(1, "lat", Types.FloatType.get()),
                        Types.NestedField.optional(2, "long", Types.FloatType.get())))));

    Record record = new Record(AvroSchemaUtil.convert(writeSchema, "table"));
    record.put("id", 34L);
    Record location =
        new Record(
            AvroSchemaUtil.fromOption(
                AvroSchemaUtil.fromOption(record.getSchema().getField("location").schema())
                    .getValueType()));
    location.put("lat", 52.995143f);
    location.put("long", -1.539054f);
    record.put("location", ImmutableMap.of("l1", location));

    // Table mapping does not project `location` map
    NameMapping nameMapping =
        MappingUtil.create(new Schema(Types.NestedField.required(0, "id", Types.LongType.get())));

    Schema readSchema = writeSchema;

    Record projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    // field id 5 comes from read schema
    assertThat(projected.get("location")).as("location field should not be read").isNull();
    assertThat(projected.get("id")).isEqualTo(34L);

    // Table mapping partially project `location` map value
    nameMapping =
        MappingUtil.create(
            new Schema(
                Types.NestedField.required(0, "id", Types.LongType.get()),
                Types.NestedField.optional(
                    5,
                    "location",
                    Types.MapType.ofOptional(
                        6,
                        7,
                        Types.StringType.get(),
                        Types.StructType.of(
                            Types.NestedField.required(1, "lat", Types.FloatType.get()))))));

    projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    Record projectedL1 = ((Map<String, Record>) projected.get("location")).get("l1");
    assertThat(projectedL1.get("long")).as("location.value.long, should not be read").isNull();
  }

  @Test
  public void testComplexMapKeys() throws IOException {
    Schema writeSchema =
        new Schema(
            Types.NestedField.required(
                5,
                "location",
                Types.MapType.ofRequired(
                    6,
                    7,
                    Types.StructType.of(
                        Types.NestedField.required(3, "k1", Types.StringType.get()),
                        Types.NestedField.required(4, "k2", Types.StringType.get())),
                    Types.StructType.of(
                        Types.NestedField.required(1, "lat", Types.FloatType.get()),
                        Types.NestedField.optional(2, "long", Types.FloatType.get())))));

    Record record = new Record(AvroSchemaUtil.convert(writeSchema, "table"));
    org.apache.avro.Schema locationSchema = record.getSchema().getField("location").schema();
    Record locationElement = new Record(locationSchema.getElementType());
    Record locationKey = new Record(locationElement.getSchema().getField("key").schema());
    Record locationValue = new Record(locationElement.getSchema().getField("value").schema());

    locationKey.put("k1", "k1");
    locationKey.put("k2", "k2");
    locationValue.put("lat", 52.995143f);
    locationValue.put("long", -1.539054f);
    locationElement.put("key", locationKey);
    locationElement.put("value", locationValue);
    record.put("location", ImmutableList.of(locationElement));

    // project a subset of the map's value columns in NameMapping
    NameMapping nameMapping =
        MappingUtil.create(
            new Schema(
                Types.NestedField.required(
                    5,
                    "location",
                    Types.MapType.ofOptional(
                        6,
                        7,
                        Types.StructType.of(
                            Types.NestedField.required(3, "k1", Types.StringType.get()),
                            Types.NestedField.optional(4, "k2", Types.StringType.get())),
                        Types.StructType.of(
                            Types.NestedField.required(1, "lat", Types.FloatType.get()))))));

    Schema readSchema =
        new Schema(
            Types.NestedField.required(
                5,
                "location",
                Types.MapType.ofOptional(
                    6,
                    7,
                    Types.StructType.of(
                        Types.NestedField.required(3, "k1", Types.StringType.get()),
                        Types.NestedField.optional(4, "k2", Types.StringType.get())),
                    Types.StructType.of(
                        Types.NestedField.required(1, "lat", Types.FloatType.get()),
                        Types.NestedField.optional(2, "long", Types.FloatType.get())))));

    Record projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    // The data is read back as a map
    Map<Record, Record> projectedLocation = (Map<Record, Record>) projected.get("location");
    Record projectedKey = projectedLocation.keySet().iterator().next();
    Record projectedValue = projectedLocation.values().iterator().next();
    assertThat(Comparators.charSequences().compare("k1", (CharSequence) projectedKey.get("k1")))
        .isEqualTo(0);
    assertThat(Comparators.charSequences().compare("k2", (CharSequence) projectedKey.get("k2")))
        .isEqualTo(0);
    assertThat(projectedValue.get("lat")).isEqualTo(52.995143f);
    assertThat(projectedValue.get("long")).isNull();
  }

  @Test
  public void testMissingRequiredFields() {
    Schema writeSchema =
        new Schema(
            Types.NestedField.required(19, "x", Types.IntegerType.get()),
            Types.NestedField.optional(18, "y", Types.IntegerType.get()));

    Record record = new Record(AvroSchemaUtil.convert(writeSchema, "table"));
    record.put("x", 1);
    record.put("y", 2);

    // table mapping not projecting a required field 'x'
    NameMapping nameMapping =
        MappingUtil.create(
            new Schema(Types.NestedField.optional(18, "y", Types.IntegerType.get())));

    Schema readSchema = writeSchema;
    assertThatThrownBy(
            // In this case, pruneColumns result is an empty record
            () -> writeAndRead(writeSchema, readSchema, record, nameMapping))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing required field: x");
  }

  @Test
  public void testArrayProjections() throws Exception {
    Schema writeSchema =
        new Schema(
            Types.NestedField.required(0, "id", Types.LongType.get()),
            Types.NestedField.optional(
                22,
                "point",
                Types.ListType.ofOptional(
                    21,
                    Types.StructType.of(
                        Types.NestedField.required(19, "x", Types.IntegerType.get()),
                        Types.NestedField.optional(18, "y", Types.IntegerType.get())))));

    Record record = new Record(AvroSchemaUtil.convert(writeSchema, "table"));
    record.put("id", 34L);
    Record pointRecord =
        new Record(
            AvroSchemaUtil.fromOption(
                AvroSchemaUtil.fromOption(record.getSchema().getField("point").schema())
                    .getElementType()));
    pointRecord.put("x", 1);
    pointRecord.put("y", 2);
    record.put("point", ImmutableList.of(pointRecord));

    NameMapping nameMapping =
        MappingUtil.create(
            new Schema(
                // Optional array field missing.
                Types.NestedField.required(0, "id", Types.LongType.get())));

    Schema readSchema = writeSchema;

    Record projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    assertThat(projected.get("point")).as("point is not projected").isNull();
    assertThat(projected.get("id")).isEqualTo(34L);
    // point array is partially projected
    nameMapping =
        MappingUtil.create(
            new Schema(
                Types.NestedField.required(0, "id", Types.LongType.get()),
                Types.NestedField.optional(
                    22,
                    "point",
                    Types.ListType.ofOptional(
                        21,
                        Types.StructType.of(
                            Types.NestedField.required(19, "x", Types.IntegerType.get()))))));

    projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    Record point = ((List<Record>) projected.get("point")).get(0);
    assertThat(point.get("x")).as("point.x is projected").isEqualTo(1);
    assertThat(point.get("y")).as("point.y is not projected").isNull();
    assertThat(projected.get("id")).isEqualTo(34L);
  }

  @Test
  public void testAliases() throws IOException {
    Schema writeSchema =
        new Schema(
            Types.NestedField.optional(
                22,
                "points",
                Types.ListType.ofOptional(
                    21,
                    Types.StructType.of(
                        Types.NestedField.required(19, "x", Types.IntegerType.get())))));

    Record record = new Record(AvroSchemaUtil.convert(writeSchema, "table"));
    Record pointRecord =
        new Record(
            AvroSchemaUtil.fromOption(
                AvroSchemaUtil.fromOption(record.getSchema().getField("points").schema())
                    .getElementType()));
    pointRecord.put("x", 1);
    record.put("points", ImmutableList.of(pointRecord));

    NameMapping nameMapping =
        NameMapping.of(
            MappedFields.of(
                MappedField.of(
                    22,
                    "points",
                    MappedFields.of(
                        MappedField.of(
                            21,
                            "element",
                            MappedFields.of(MappedField.of(19, Lists.newArrayList("x"))))))));

    Schema readSchema =
        new Schema(
            Types.NestedField.optional(
                22,
                "points",
                Types.ListType.ofOptional(
                    21,
                    Types.StructType.of(
                        // x renamed to y
                        Types.NestedField.required(19, "y", Types.IntegerType.get())))));

    Record projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    assertThat(((List<Record>) projected.get("points")).get(0).get("y"))
        .as("x is read as y")
        .isEqualTo(1);

    readSchema =
        new Schema(
            Types.NestedField.optional(
                22,
                "points",
                Types.ListType.ofOptional(
                    21,
                    Types.StructType.of(
                        // x renamed to z
                        Types.NestedField.required(19, "z", Types.IntegerType.get())))));

    projected = writeAndRead(writeSchema, readSchema, record, nameMapping);
    assertThat(((List<Record>) projected.get("points")).get(0).get("z"))
        .as("x is read as z")
        .isEqualTo(1);
  }

  @Test
  public void testInferredMapping() throws IOException {
    Schema writeSchema =
        new Schema(
            Types.NestedField.required(0, "id", Types.LongType.get()),
            Types.NestedField.optional(1, "data", Types.StringType.get()));

    Record record = new Record(AvroSchemaUtil.convert(writeSchema, "table"));
    record.put("id", 34L);
    record.put("data", "data");

    Schema readSchema = writeSchema;
    // Pass null for nameMapping so that it is automatically inferred from read schema
    Record projected = writeAndRead(writeSchema, readSchema, record, null);
    assertThat(projected).isEqualTo(record);
  }

  @Test
  public void testVariantNameMapping() {
    Schema icebergSchema =
        new Schema(
            Types.NestedField.required(0, "id", Types.LongType.get()),
            Types.NestedField.required(1, "var", Types.VariantType.get()));

    org.apache.avro.Schema avroSchema = RemoveIds.removeIds(icebergSchema);
    assertThat(AvroSchemaUtil.hasIds(avroSchema)).as("Expect schema has no ids").isFalse();

    NameMapping nameMapping =
        NameMapping.of(
            MappedField.of(0, ImmutableList.of("id")), MappedField.of(1, ImmutableList.of("var")));
    org.apache.avro.Schema mappedSchema = AvroSchemaUtil.applyNameMapping(avroSchema, nameMapping);
    assertThat(mappedSchema).isEqualTo(AvroSchemaUtil.convert(icebergSchema.asStruct(), "table"));
  }

  @Test
  @Override
  public void testAvroArrayAsLogicalMap() {
    // no-op
  }

  @Override
  protected Record writeAndRead(
      String desc, Schema writeSchema, Schema readSchema, Record inputRecord) throws IOException {

    // Use all existing TestAvroReadProjection tests to verify that
    // we get the same projected Avro record whether we use
    // NameMapping together with file schema without field-ids or we
    // use a file schema having field-ids
    Record record = super.writeAndRead(desc, writeSchema, readSchema, inputRecord);
    Record projectedWithNameMapping =
        writeAndRead(writeSchema, readSchema, inputRecord, MappingUtil.create(writeSchema));
    assertThat(projectedWithNameMapping).isEqualTo(record);
    return record;
  }

  private Record writeAndRead(
      Schema writeSchema, Schema readSchema, Record record, NameMapping nameMapping)
      throws IOException {

    File file = temp.resolve("test.avro").toFile();
    // Write without file ids
    org.apache.avro.Schema writeAvroSchema = RemoveIds.removeIds(writeSchema);
    DatumWriter<Record> datumWriter = new GenericDatumWriter<>(writeAvroSchema);
    try (DataFileWriter<Record> dataFileWriter = new DataFileWriter<>(datumWriter)) {
      dataFileWriter.create(writeAvroSchema, file);
      dataFileWriter.append(record);
    }

    Iterable<GenericData.Record> records =
        Avro.read(Files.localInput(file)).project(readSchema).withNameMapping(nameMapping).build();

    return Iterables.getOnlyElement(records);
  }
}
