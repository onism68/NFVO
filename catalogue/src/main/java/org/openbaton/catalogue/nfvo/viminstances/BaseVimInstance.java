/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.openbaton.catalogue.nfvo;

import org.openbaton.catalogue.mano.common.DeploymentFlavour;
import org.openbaton.catalogue.util.BaseEntity;

import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** Created by lto on 12/05/15. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"name", "projectId"}))
public class BaseVimInstance extends BaseEntity {

  @NotNull
  @Size(min = 1)
  private String name;

  @NotNull
  @Size(min = 1)
  private String authUrl;

  private String tenant;

  @NotNull
  @Size(min = 1)
  private String username;

  @NotNull
  @Size(min = 1)
  private String password;

  private String keyPair;

  @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
  private Location location;

  @ElementCollection(fetch = FetchType.EAGER)
  private Set<String> securityGroups;

  @OneToMany(
    fetch = FetchType.EAGER,
    cascade = {CascadeType.ALL}
  )
  private Set<DeploymentFlavour> flavours;

  private String type;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
  private Set<NFVImage> images;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
  private Set<Network> networks;

  private Boolean active = true;

  public BaseVimInstance() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getAuthUrl() {
    return authUrl;
  }

  public void setAuthUrl(String authUrl) {
    this.authUrl = authUrl;
  }

  public String getTenant() {
    return tenant;
  }

  public void setTenant(String tenant) {
    this.tenant = tenant;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getKeyPair() {
    return keyPair;
  }

  public void setKeyPair(String keyPair) {
    this.keyPair = keyPair;
  }

  public Location getLocation() {
    return location;
  }

  public void setLocation(Location location) {
    this.location = location;
  }

  public Set<String> getSecurityGroups() {
    return securityGroups;
  }

  public void setSecurityGroups(Set<String> securityGroups) {
    this.securityGroups = securityGroups;
  }

  public Set<DeploymentFlavour> getFlavours() {
    return flavours;
  }

  public void setFlavours(Set<DeploymentFlavour> flavours) {
    this.flavours = flavours;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Set<NFVImage> getImages() {
    return images;
  }

  public void setImages(Set<NFVImage> images) {
    this.images = images;
  }

  public Set<Network> getNetworks() {
    return networks;
  }

  public void setNetworks(Set<Network> networks) {
    this.networks = networks;
  }

  @Override
  public String toString() {
    return "VimInstance{"
        + "name='"
        + name
        + '\''
        + ", authUrl='"
        + authUrl
        + '\''
        + ", tenant='"
        + tenant
        + '\''
        + ", username='"
        + username
        + '\''
        + ", password='"
        + password
        + '\''
        + ", keyPair='"
        + keyPair
        + '\''
        + ", location="
        + location
        + ", securityGroups="
        + securityGroups
        + ", flavours="
        + flavours
        + ", type='"
        + type
        + '\''
        + ", images="
        + images
        + ", networks="
        + networks
        + ", active="
        + active
        + "} "
        + super.toString();
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public Boolean isActive() {
    return active;
  }
}