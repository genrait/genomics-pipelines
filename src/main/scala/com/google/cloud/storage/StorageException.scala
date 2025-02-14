/*
 * Copyright 2019 The Glow Authors
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

package com.google.cloud.storage

/**
 * GATK has a messy dependency on the google cloud SDK even when you don't want to use gcloud ;_;.
 *
 * To avoid actually including all the google jars, we include dummy versions of classes that
 * are imported by the subcomponents of GATK that we rely on.
 */
class StorageException extends RuntimeException {}
