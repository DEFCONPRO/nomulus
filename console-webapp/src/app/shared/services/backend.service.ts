// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { catchError, Observable, of } from 'rxjs';
import { SecuritySettingsBackendModel } from 'src/app/settings/security/security.service';

import { Contact } from '../../settings/contact/contact.service';
import { Registrar } from '../../registrar/registrar.service';
import { UserData } from './userData.service';

@Injectable()
export class BackendService {
  constructor(private http: HttpClient) {}

  errorCatcher<Type>(
    error: HttpErrorResponse,
    mockData?: Type
  ): Observable<Type> {
    if (error.error instanceof Error) {
      // A client-side or network error occurred. Handle it accordingly.
      console.error('An error occurred:', error.error.message);
    } else {
      // The backend returned an unsuccessful response code.
      // The response body may contain clues as to what went wrong,
      console.error(
        `Backend returned code ${error.status}, body was: ${error.error}`
      );
    }

    //   return throwError(() => {throw "Failed"});
    return of(<Type>mockData);
  }

  getContacts(registrarId: string): Observable<Contact[]> {
    return this.http
      .get<Contact[]>(
        `/console-api/settings/contacts?registrarId=${registrarId}`
      )
      .pipe(catchError((err) => this.errorCatcher<Contact[]>(err)));
  }

  postContacts(
    registrarId: string,
    contacts: Contact[]
  ): Observable<Contact[]> {
    return this.http.post<Contact[]>(
      `/console-api/settings/contacts?registrarId=${registrarId}`,
      contacts
    );
  }

  getRegistrars(): Observable<Registrar[]> {
    return this.http
      .get<Registrar[]>('/console-api/registrars')
      .pipe(catchError((err) => this.errorCatcher<Registrar[]>(err)));
  }

  getSecuritySettings(
    registrarId: string
  ): Observable<SecuritySettingsBackendModel> {
    return this.http
      .get<SecuritySettingsBackendModel>(
        `/console-api/settings/security?registrarId=${registrarId}`
      )
      .pipe(
        catchError((err) =>
          this.errorCatcher<SecuritySettingsBackendModel>(err)
        )
      );
  }

  postSecuritySettings(
    registrarId: string,
    securitySettings: SecuritySettingsBackendModel
  ): Observable<SecuritySettingsBackendModel> {
    return this.http.post<SecuritySettingsBackendModel>(
      `/console-api/settings/security?registrarId=${registrarId}`,
      securitySettings
    );
  }

  getUserData(): Observable<UserData> {
    return this.http
      .get<UserData>(`/console-api/userdata`)
      .pipe(catchError((err) => this.errorCatcher<UserData>(err)));
  }
}
