# InsureDirectory - simple member and agent management tool

Create a new sub-project that is part of the current project called InsureDirectory.

The sub-project will focus on managing the ldap directory that is in the current project. It will do so in a specific and predictable way. Some of the front end interface will be supported by an internal database, or retrieved from the ldap directory. It is similar to phpldapadmin but will provide simpler interfaces - interfaces for non-tech, non-ldap, end users (such as CSR's).

The tech stack will be:

    * backend:

      * Quarkus with Apache Camel (REST endpoints)
      * SQLite (internal database)

    * frontend:
  
      * typescript
      * angular
      * css grid layout (styling of pages)

Features:

    * CRUD functionality for all attributes in directory
    * Interfaces are broken out by user type (ex. member, agent, etc.) or attribute type (accounts [agency, agreement, book], etc.)
    * a page to create ldap queries for use by developers - both ldapsearch command and java code that has ldap search (so another app can execute query)
